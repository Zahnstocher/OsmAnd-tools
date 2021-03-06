package net.osmand.osm.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.osmand.PlatformUtil;
import net.osmand.data.preparation.DBDialect;
import net.osmand.util.sql.SqlInsertValuesReader;
import net.osmand.util.sql.SqlInsertValuesReader.InsertValueProcessor;

import org.apache.commons.logging.Log;

public class SearchDBCreator {

	private static final Log log = PlatformUtil.getLog(SearchDBCreator.class);
	private static final int BATCH_SIZE = 500;

	public static void main(String[] args) throws SQLException, IOException {
		boolean uncompressed = false;
		String workingDir = "/home/user/osmand/wikivoyage/";
		if (args.length > 1) {
			workingDir = args[0];
			uncompressed = Boolean.parseBoolean(args[1]);
		}
		File pathTodb = new File(workingDir, (uncompressed ? "full_wikivoyage.sqlite" : "wikivoyage.sqlite"));
		final File langlinkFolder = new File(workingDir, "langlinks");
		final File langlinkFile = new File(workingDir, "langlink.sqlite");
		DBDialect dialect = DBDialect.SQLITE;
		Connection conn = (Connection) dialect.getDatabaseConnection(pathTodb.getAbsolutePath(), log);
		
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_orig_id ON travel_articles(original_id);");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_image_title ON travel_articles(image_title);");
		
		System.out.println("Processing langlink file " + langlinkFile.getAbsolutePath());
		createLangLinksIfMissing(langlinkFile, langlinkFolder, conn);
		System.out.println("Connect translations ");
		generateSameTripIdForDifferentLang(langlinkFile, conn);
		System.out.println("Generate missing ids");
		generateIdsIfMissing(conn, langlinkFile);
		System.out.println("Download/Copy proper headers for articles");
		updateProperHeaderForArticles(conn, workingDir);
		System.out.println("Copy headers between lang");
		copyHeaders(conn);
		
		System.out.println("Generate agg part of");
		generateAggPartOf(conn);
		System.out.println("Generate search table");
		generateSearchTable(conn);
		
		conn.createStatement().execute("DROP INDEX IF EXISTS index_orig_id");
		conn.createStatement().execute("DROP INDEX IF EXISTS index_image_title ");
		conn.close();
	}

	private static void updateProperHeaderForArticles(Connection conn, String workingDir) throws SQLException {
		final File imagesMetadata = new File(workingDir, "images.sqlite");
		// delete images to fully recreate db
		// imagesMetadata.delete();
		Connection imagesConn = (Connection) DBDialect.SQLITE.getDatabaseConnection(imagesMetadata.getAbsolutePath(), log);
		imagesConn.createStatement()
				.execute("CREATE TABLE IF NOT EXISTS images(file text, url text, metadata text, sourcefile text)");
		conn.createStatement().execute("DROP TABLE IF EXISTS source_image;");
		conn.createStatement().execute("CREATE TABLE IF NOT EXISTS source_image(banner_image text, source_image text)");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_source_image ON source_image(banner_image);");
		
		Map<String, String> existingImagesMapping = new LinkedHashMap<String, String>();
		TreeSet<String> sourceImages = new TreeSet<String>();
		ResultSet rs1 = imagesConn.createStatement().executeQuery("SELECT file, sourcefile FROM images");
		while(rs1.next()) {
			String sourceFile = rs1.getString(2);
			sourceFile = stripImageName(sourceFile);
			existingImagesMapping.put(rs1.getString(1), sourceFile);
			if(sourceFile != null) {
				sourceImages.add(rs1.getString(2));
			}
		}
		rs1.close();
		
		
		Map<String, String> valuesToUpdate = new LinkedHashMap<String, String>();
//		PreparedStatement pSelect = imagesConn.prepareStatement("SELECT file, url, metadata, sourcefile FROM images WHERE file = ?");
		//PreparedStatement pDelete = imagesConn.prepareStatement("DELETE FROM images WHERE file = ?");
		PreparedStatement pInsert = imagesConn.prepareStatement("INSERT INTO images(file, url, metadata, sourcefile) VALUES (?, ?, ?, ?)");
		ResultSet rs = conn.createStatement().executeQuery("SELECT distinct image_title, title, lang FROM travel_articles where image_title <> ''");
		PreparedStatement pInsertSource = conn.prepareStatement("INSERT INTO source_image(banner_image, source_image) VALUES(?, ?)");
		
		int imagesFetched = 0;
		int imagesProcessed = 0;
		int imagesToUpdate = 0;
		while (rs.next()) {
			String imageTitle = rs.getString(1);
			String name = rs.getString(2);
			String lang = rs.getString(3);
			if(imageTitle == null || imageTitle.length() == 0) {
				continue;
			}
			if (valuesToUpdate.containsKey(imageTitle)) {
				continue;
			}
			if(sourceImages.contains(imageTitle)) {
				// processed before
				continue;
			}
			if (imagesProcessed++ % 5000 == 0) {
				System.out.println("Images metadata processed: " + imagesProcessed);
			}
			if(!existingImagesMapping.containsKey(imageTitle)) {
				existingImagesMapping.put(imageTitle, null);
				String metadataUrl = "https://commons.wikimedia.org/w/index.php?title=File:" + imageTitle + "&action=raw";
				try {
					URL url = new URL(metadataUrl);
					BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
					StringBuilder metadata = new StringBuilder();
					String s;
					String sourceFile = null;
					while ((s = reader.readLine()) != null) {
						if (s.contains("source=") && s.contains("File:")) {
							sourceFile = s.substring(s.indexOf("File:") + "File:".length());
							sourceFile = stripImageName(sourceFile);
						}
						metadata.append(s).append("\n");
					}
					pInsert.setString(1, imageTitle);
					pInsert.setString(2, metadataUrl);
					pInsert.setString(3, metadata.toString());
					pInsert.setString(4, sourceFile);
					pInsert.executeUpdate();
					existingImagesMapping.put(imageTitle, sourceFile);
					if (imagesFetched++ % 100 == 0) {
						System.out.println("Images metadata fetched: " + imagesFetched);
					}
				} catch (IOException e) {
					System.err.println("Error fetching image " + imageTitle + " " + lang + ":" + name + " "
							+ e.getMessage());
				}
			}
			String sourceFile = existingImagesMapping.get(imageTitle);
			
			valuesToUpdate.put(imageTitle, sourceFile);
			if (sourceFile != null && sourceFile.trim().length() > 0) {
				pInsertSource.setString(1, imageTitle);
				pInsertSource.setString(2, sourceFile);
				pInsertSource.executeUpdate();
				imagesToUpdate++;
			}
		}
		rs.close();
		System.out.println("Updating images " + imagesToUpdate + ".");
		int updated = conn.createStatement().executeUpdate("UPDATE travel_articles SET image_title = "
				+ " (SELECT source_image from source_image s where s.banner_image = travel_articles.image_title) "
				+ " WHERE image_title IN (SELECT distinct banner_image from source_image)");
		System.out.println("Update to full size images finished, updated: " + updated);
		
		imagesConn.close();
		
	}

	private static String stripImageName(String sourceFile) {
		if(sourceFile == null) {
			return null;
		}
		if (sourceFile.contains("]")) {
			sourceFile = sourceFile.substring(0, sourceFile.indexOf(']'));
		}
		if (sourceFile.contains("}")) {
			sourceFile = sourceFile.substring(0, sourceFile.indexOf('}'));
		}
		if(sourceFile.contains("[[") || sourceFile.contains("|")) {
			return null;
		}
		return sourceFile;
	}

	private static void copyHeaders(Connection conn) throws SQLException {
		Statement statement = conn.createStatement();
		boolean update = statement.execute("update or ignore travel_articles set image_title=(SELECT image_title FROM travel_articles t "
				+ "WHERE t.trip_id = travel_articles.trip_id and t.lang = 'en')"
				+ " where travel_articles.image_title='' and travel_articles.lang <>'en'");
        System.out.println("Copy headers from english language to others: " + update);
        statement.close();
        statement = conn.createStatement();
        System.out.println("Articles without banner image:");
        ResultSet rs = statement.executeQuery("select count(*), lang from travel_articles where image_title = '' group by lang");
        while(rs.next()) {
        	System.out.println("\t" + rs.getString(2) + " " + rs.getInt(1));
        }
		rs.close();
		statement.close();
	}

	private static void generateAggPartOf(Connection conn) throws SQLException {
		try {
			conn.createStatement().execute("ALTER TABLE travel_articles ADD COLUMN aggregated_part_of");
		} catch (Exception e) {
			System.err.println("Column aggregated_part_of already exists");
		}
		PreparedStatement updatePartOf = conn
				.prepareStatement("UPDATE travel_articles SET aggregated_part_of = ? WHERE title = ? AND lang = ?");
		PreparedStatement data = conn.prepareStatement("SELECT trip_id, title, lang, is_part_of FROM travel_articles");
		ResultSet rs = data.executeQuery();
		int batch = 0;
		while (rs.next()) {
			String title = rs.getString("title");
			String lang = rs.getString("lang");
			updatePartOf.setString(1, getAggregatedPartOf(conn, rs.getString("is_part_of"), lang));
			updatePartOf.setString(2, title);
			updatePartOf.setString(3, lang);
			updatePartOf.addBatch();
			if (batch++ > BATCH_SIZE) {
				updatePartOf.executeBatch();
				batch = 0;
			}
		}
		finishPrep(updatePartOf);
		data.close();
		rs.close();
	}

	private static void generateSearchTable(Connection conn) throws SQLException {
		conn.createStatement().execute("DROP TABLE IF EXISTS travel_search;");
		conn.createStatement()
				.execute("CREATE TABLE travel_search(search_term text, trip_id long, article_title text, lang text)");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_search_term ON travel_search(search_term);");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_search_city ON travel_search(trip_id)");

		PreparedStatement insertSearch = conn.prepareStatement("INSERT INTO travel_search VALUES (?, ?, ?, ?)");
		PreparedStatement data = conn.prepareStatement("SELECT trip_id, title, lang, is_part_of FROM travel_articles");

		ResultSet rs = data.executeQuery();
		int batch = 0;
		while (rs.next()) {
			String title = rs.getString("title");
			String titleToSplit = title.replaceAll("[/\\)\\(-]", " ").replaceAll(" +", " ");
			String lang = rs.getString("lang");
			long id = rs.getLong("trip_id");
			for (String s : titleToSplit.split(" ")) {
				insertSearch.setString(1, s.toLowerCase());
				insertSearch.setLong(2, id);
				insertSearch.setString(3, title);
				insertSearch.setString(4, lang);
				insertSearch.addBatch();
				if (batch++ > 500) {
					insertSearch.executeBatch();
					batch = 0;
				}
			}
		}
		finishPrep(insertSearch);
		data.close();
		rs.close();
	}

	private static void generateSameTripIdForDifferentLang(final File langlinkFile, Connection conn)
			throws SQLException {
		DBDialect dialect = DBDialect.SQLITE;
		Connection langlinkConn = (Connection) dialect.getDatabaseConnection(langlinkFile.getAbsolutePath(), log);
		PreparedStatement langlinkStatement = langlinkConn
				.prepareStatement("SELECT id FROM langlinks WHERE title = ? AND lang = ?");
		PreparedStatement updateTripId = conn
				.prepareStatement("UPDATE travel_articles SET trip_id = ? WHERE title = ? AND lang = ?");
		PreparedStatement data = conn.prepareStatement("SELECT trip_id, title, lang, is_part_of FROM travel_articles");

		ResultSet rs = data.executeQuery();
		int batch = 0;
		while (rs.next()) {
			String title = rs.getString("title");
			String lang = rs.getString("lang");
			long id = getCityId(langlinkStatement, title, lang);
			updateTripId.setLong(1, id);
			updateTripId.setString(2, title);
			updateTripId.setString(3, lang);
			updateTripId.addBatch();
			if (batch++ > BATCH_SIZE) {
				updateTripId.executeBatch();
				batch = 0;
			}
		}
		finishPrep(updateTripId);
		langlinkStatement.close();
		langlinkConn.close();
	}

	private static long getCityId(PreparedStatement langlinkStatement, String title, String lang) throws SQLException {
		langlinkStatement.setString(1, title);
		langlinkStatement.setString(2, lang);
		ResultSet rs = langlinkStatement.executeQuery();
		if (rs.next()) {
			return rs.getLong("id");
		}
		return 0;
	}

	private static void createLangLinksIfMissing(File langlinkFile, File langlinkFolder, Connection conn)
			throws IOException, SQLException {
		if (langlinkFolder.exists() && !langlinkFile.exists()) {
			processLangLinks(langlinkFolder, langlinkFile, conn);
		}
	}

	private static void processLangLinks(File langlinkFolder, File langlinkFile, Connection wikivoyageConnection)
			throws IOException, SQLException {
		if (!langlinkFolder.isDirectory()) {
			System.err.println("Specified langlink folder is not a directory");
			System.exit(-1);
		}
		DBDialect dialect = DBDialect.SQLITE;
		Connection conn = (Connection) dialect.getDatabaseConnection(langlinkFile.getAbsolutePath(), log);
		conn.createStatement()
				.execute("CREATE TABLE langlinks (id long NOT NULL DEFAULT 0, lang text NOT NULL DEFAULT '', "
						+ "title text NOT NULL DEFAULT '', UNIQUE (lang, title) ON CONFLICT IGNORE)");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_title ON langlinks(title);");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_lang ON langlinks(lang);");
		PreparedStatement prep = conn.prepareStatement("INSERT OR IGNORE INTO langlinks VALUES (?, ?, ?)");
		PreparedStatement articleQuery = wikivoyageConnection
				.prepareStatement("SELECT title FROM travel_articles WHERE original_id = ? AND lang = ?");
		Set<Long> ids = new HashSet<>();
		Set<Long> currentFileIds = new HashSet<>();
		Map<Long, Long> currMapping = new HashMap<>();
		File[] files = langlinkFolder.listFiles();
		final String[] lang = new String[1];
		InsertValueProcessor p = new InsertValueProcessor() {

			private int batch = 0;
			private long maxId = 0;

			@Override
			public void process(List<String> insValues) {
				long id = Long.valueOf(insValues.get(0));
				try {
					articleQuery.setLong(1, id);
					articleQuery.setString(2, lang[0]);
					ResultSet rs = articleQuery.executeQuery();
					String thisTitle = "";
					while (rs.next()) {
						thisTitle = rs.getString("title");
					}
					articleQuery.clearParameters();
					maxId = Math.max(maxId, id);
					Long genId = currMapping.get(id);
					if (genId == null) {
						if (ids.contains(id)) {
							genId = maxId++;
							currMapping.put(id, genId);
						}
					}
					id = genId == null ? id : genId;
					currentFileIds.add(id);
					if (!thisTitle.isEmpty()) {
						prep.setLong(1, id);
						prep.setString(2, lang[0]);
						prep.setString(3, thisTitle);
						prep.addBatch();
						batch++;
					}
					prep.setLong(1, id);
					prep.setString(2, insValues.get(1));
					prep.setString(3, insValues.get(2));
					prep.addBatch();
					if (batch++ > BATCH_SIZE) {
						prep.executeBatch();
						batch = 0;
					}
				} catch (SQLException e) {
					System.err.println(e.getMessage());
				}
			}
		};
		for (File f : files) {
			lang[0] = f.getName().replace("wikivoyage-latest-langlinks.sql.gz", "");
			SqlInsertValuesReader.readInsertValuesFile(f.getAbsolutePath(), p);
			ids.addAll(currentFileIds);
			currentFileIds.clear();
			currMapping.clear();
		}
		prep.addBatch();
		prep.executeBatch();
		prep.close();
		conn.createStatement().execute("DROP INDEX IF EXISTS index_orig_id;");
		articleQuery.close();
		conn.close();
	}

	private static void finishPrep(PreparedStatement ps) throws SQLException {
		ps.addBatch();
		ps.executeBatch();
		ps.close();
	}

	private static String getAggregatedPartOf(Connection conn, String partOf, String lang) throws SQLException {
		if (partOf.isEmpty()) {
			return "";
		}
		StringBuilder res = new StringBuilder();
		res.append(partOf);
		res.append(",");
		PreparedStatement ps = conn
				.prepareStatement("SELECT is_part_of FROM travel_articles WHERE title = ? AND lang = '" + lang + "'");
		String prev = "";
		while (true) {
			ps.setString(1, partOf);
			ResultSet rs = ps.executeQuery();
			String buf = "";
			while (rs.next()) {
				buf = rs.getString(1);
			}
			if (buf.equals("") || buf.equals(partOf) || buf.equals(prev)) {
				ps.close();
				rs.close();
				return res.toString().substring(0, res.length() - 1);
			} else {
				rs.close();
				ps.clearParameters();
				res.append(buf);
				res.append(',');
				prev = partOf;
				partOf = buf;
			}
		}
	}

	private static void generateIdsIfMissing(Connection conn, File langlinkfile) throws SQLException {
		long maxId = 0;
		DBDialect dialect = DBDialect.SQLITE;
		Connection langConn = (Connection) dialect.getDatabaseConnection(langlinkfile.getAbsolutePath(), log);
		Statement st = langConn.createStatement();
		ResultSet rs = st.executeQuery("SELECT MAX(id) FROM langlinks");
		if (rs.next()) {
			maxId = rs.getLong(1) + 1;
		}
		st.close();
		rs.close();

		langConn.close();
		if (maxId == 0) {
			System.err.println("MAX ID is 0");
			throw new IllegalStateException();
		}
		int batch = 0;
		Statement ps = conn.createStatement();
		PreparedStatement prep = conn
				.prepareStatement("UPDATE travel_articles SET trip_id = ? WHERE title = ? AND lang = ?");
		ResultSet res = ps.executeQuery("SELECT title, lang FROM travel_articles WHERE trip_id = 0");
		int updated = 0;
		while (res.next()) {
			updated++;
			String title = res.getString("title");
			String lang = res.getString("lang");
			prep.setLong(1, maxId++);
			prep.setString(2, title);
			prep.setString(3, lang);
			prep.addBatch();
			if (batch++ > 500) {
				prep.executeBatch();
				batch = 0;
			}
		}
		prep.addBatch();
		prep.executeBatch();
		prep.close();
		res.close();
		ps.close();

		System.out.println("Updated " + updated + " trip_id with max id " + maxId);
		Statement st2 = conn.createStatement();
		rs = st2.executeQuery("SELECT count(*) FROM travel_articles WHERE trip_id = 0");
		if (rs.next()) {
			System.out.println("Count travel articles with empty trip_id: " + rs.getInt(1));

		}
		rs.close();
		st2.close();

	}
}
