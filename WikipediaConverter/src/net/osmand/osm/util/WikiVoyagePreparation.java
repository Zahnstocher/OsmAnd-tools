package net.osmand.osm.util;

import info.bliki.wiki.filter.Encoder;
import info.bliki.wiki.filter.HTMLConverter;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.osmand.PlatformUtil;
import net.osmand.data.preparation.DBDialect;
import net.osmand.impl.ConsoleProgressImplementation;
import net.osmand.osm.util.GPXUtils.GPXFile;
import net.osmand.osm.util.GPXUtils.WptPt;
import net.osmand.osm.util.WikiDatabasePreparation.LatLon;

import org.apache.commons.logging.Log;
import org.apache.tools.bzip2.CBZip2InputStream;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.xwiki.component.manager.ComponentLookupException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class WikiVoyagePreparation {
	private static final Log log = PlatformUtil.getLog(WikiDatabasePreparation.class);
		
	private static WikivoyageImageLinksStorage imageStorage;
	private static boolean imageLinks;
	private static boolean uncompressed;
	private static String folderPath;
		
	public enum WikivoyageTemplates {
		LOCATION("geo"),
		POI("poi"),
		PART_OF("part_of"),
		BANNER("pagebanner");
		
		private String type;
		WikivoyageTemplates(String s) {
			type = s;
		}
		
		public String getType() {
			return type;
		}
	}
	
	public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException, SQLException, ComponentLookupException {
		String lang = "";
		String folder = "";
		if(args.length == 0) {
			lang = "en";
			folder = "/home/paul/osmand/wikivoyage/";
			imageLinks = false;
			uncompressed = false;
		}
		if(args.length > 0) {
			lang = args[0];
		}
		if(args.length > 1){
			folder = args[1];
		}
		if(args.length > 2){
			imageLinks = args[2].equals("fetch_image_links");
			uncompressed = args[2].equals("uncompressed");
		}
		if (args.length > 3) {
			uncompressed = args[3].equals("uncompressed");
		}
		if (imageLinks) {
			System.out.println("Processing the image links for " + lang + " articles");
			imageStorage = new WikivoyageImageLinksStorage(lang, folder);
		}
		folderPath = folder;
		final String wikiPg = folder + lang + "wikivoyage-latest-pages-articles.xml.bz2";
		final String langlink = folder + lang + "wikivoyage-latest-langlinks.sql.gz";
		if (!new File(wikiPg).exists()) {
			System.out.println("Dump for " + lang + " doesn't exist");
			return;
		}
		final String sqliteFileName = folder + (uncompressed ? "full_" : "") + "wikivoyage.sqlite";
		processWikivoyage(wikiPg, lang, sqliteFileName);
//		processLangLinks(langlink, lang, sqliteFileName);
		System.out.println("Successfully generated.");
    }

	private static void processLangLinks(String langlink, String lang, String sqliteFileName) throws IOException, SQLException {
		InputStream streamFile = new BufferedInputStream(new FileInputStream(langlink), 8192 * 4);
		InputStream stream = streamFile;
		GZIPInputStream gzis = new GZIPInputStream(stream);
		Reader reader = new InputStreamReader(gzis,"UTF-8");
		InputSource is = new InputSource(reader);
		is.setEncoding("UTF-8");
		BufferedReader in = new BufferedReader(reader);
		String line;
		DBDialect dialect = DBDialect.SQLITE;
		Connection conn;
		conn = (Connection) dialect.getDatabaseConnection(sqliteFileName, log);
		conn.createStatement().execute("CREATE TABLE IF NOT EXISTS langlinks (id int(10) NOT NULL DEFAULT 0, "
				+ "lang text NOT NULL DEFAULT '', "
				+ "title text UNIQUE NOT NULL DEFAULT '')");
		conn.createStatement().execute("CREATE INDEX index_id ON langlinks(id);");
		PreparedStatement prep = conn.prepareStatement("INSERT OR IGNORE INTO langlinks VALUES (?, ?, ?)");
		int batch = 0;
		while((line = in.readLine()) != null) {
		    if (line.startsWith("INSERT INTO")) {
		    	line = line.substring(line.indexOf("(") + 1);
		    	line = line.replaceAll("\\),\\(", "|");
		    	String[] vals = line.split("\\|");
		    	for (String s : vals) {
		    		s = s.replaceAll("'", "").replace(");", "");
		    		String[] values = s.split(",");
		    		if (values.length == 3) {
		    			prep.setInt(1, Integer.valueOf(values[0]));
			    		prep.setString(2, values[1]);
			    		prep.setString(3, values[2]);
			    		prep.addBatch();
			    		if (batch++ > 500) {
			    			prep.executeBatch();
							batch = 0;
			    		}
		    		}
		    	}
		    }
		}
		stream.close();
		streamFile.close();
		gzis.close();
		prep.addBatch();
    	prep.executeBatch();
    	prep.close();
		conn.close();
	}

	protected static void processWikivoyage(final String wikiPg, String lang, String sqliteFileName)
			throws ParserConfigurationException, SAXException, FileNotFoundException, IOException, SQLException, ComponentLookupException {
		SAXParser sx = SAXParserFactory.newInstance().newSAXParser();
		InputStream streamFile = new BufferedInputStream(new FileInputStream(wikiPg), 8192 * 4);
		InputStream stream = streamFile;
		if (stream.read() != 'B' || stream.read() != 'Z') {
			stream.close();
			throw new RuntimeException(
					"The source stream must start with the characters BZ if it is to be read as a BZip2 stream."); //$NON-NLS-1$
		} 
		CBZip2InputStream zis = new CBZip2InputStream(stream);
		Reader reader = new InputStreamReader(zis,"UTF-8");
		InputSource is = new InputSource(reader);
		is.setEncoding("UTF-8");
		final WikiOsmHandler handler = new WikiOsmHandler(sx, streamFile, lang, new File(sqliteFileName));
		sx.parse(is, handler);
		handler.finish();
	}
	
	public static class WikiOsmHandler extends DefaultHandler {
		long id = 1;
		private final SAXParser saxParser;
		private boolean page = false;
		private boolean revision = false;
		
		private StringBuilder ctext = null;
		private long cid;
		private StringBuilder title = new StringBuilder();
		private StringBuilder text = new StringBuilder();
		private StringBuilder pageId = new StringBuilder();
		
		private boolean parseText = false;

		private final InputStream progIS;
		private ConsoleProgressImplementation progress = new ConsoleProgressImplementation();
		private DBDialect dialect = DBDialect.SQLITE;
		private Connection conn;
		private PreparedStatement prep;
		private int batch = 0;
		private final static int BATCH_SIZE = 500;
		final ByteArrayOutputStream bous = new ByteArrayOutputStream(64000);
		private String lang;
		private Connection imageConn;
		private PreparedStatement imagePrep;
			
		WikiOsmHandler(SAXParser saxParser, InputStream progIS, String lang, File sqliteFile)
				throws IOException, SQLException, ComponentLookupException{
			this.lang = lang;
			this.saxParser = saxParser;
			this.progIS = progIS;		
			progress.startTask("Parse wiki xml", progIS.available());
			if (!imageLinks) {
				conn = (Connection) dialect.getDatabaseConnection(sqliteFile.getAbsolutePath(), log);
				String dataType = uncompressed ? "text" : "blob";
				conn.createStatement().execute("DROP TABLE IF EXISTS " + lang + "_wikivoyage");
				conn.createStatement().execute("CREATE TABLE " + lang + "_wikivoyage(article_id text, title text, content_gz" + 
						dataType + ", is_part_of text, lat double, lon double, image_title text, gpx_gz " + dataType + ", id long)");
				conn.createStatement().execute("CREATE INDEX index_title_" + lang +  " ON " + lang + "_wikivoyage(article_id);");
				conn.createStatement().execute("CREATE INDEX " + lang + "_index_part_of ON " + lang + "_wikivoyage(is_part_of);");
				prep = conn.prepareStatement("INSERT INTO " + lang + "_wikivoyage VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
				try {
					imageConn = (Connection) dialect.getDatabaseConnection(folderPath + "imageData.sqlite", log);
					imagePrep = imageConn.prepareStatement("SELECT image_url FROM image_links WHERE image_title = ?");
				} catch (Exception e) {	}
			}
		}
		
		public void addBatch() throws SQLException {
			prep.addBatch();
			if(batch++ > BATCH_SIZE) {
				prep.executeBatch();
				batch = 0;
			}
		}
		
		public void finish() throws SQLException {
			if (imageLinks) {
				imageStorage.finish();
			} else {
				prep.executeBatch();
				if (imagePrep != null) {
					imageConn.close();
					imagePrep.close();
				}
				if(!conn.getAutoCommit()) {
					conn.commit();
				}
				prep.close();
				conn.close();
			}
		}

		public int getCount() {
			return (int) (id - 1);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			if (!page) {
				page = name.equals("page");
			} else {
				if (name.equals("title")) {
					title.setLength(0);
					ctext = title;
				} else if (name.equals("text")) {
					if(parseText) {
						text.setLength(0);
						ctext = text;
					}
				} else if (name.equals("revision")) {
					revision  = true;
				} else if (name.equals("id") && !revision) {
					pageId.setLength(0);
					ctext = pageId;
				}
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			if (page) {
				if (ctext != null) {
					ctext.append(ch, start, length);
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			
			try {
				if (page) {
					if (name.equals("page")) {
						page = false;
						parseText = false;
						progress.remaining(progIS.available());
					} else if (name.equals("title")) {
						ctext = null;
					} else if (name.equals("revision")) {
						revision = false;
					} else if (name.equals("id") && !revision) {
						ctext = null;
						cid = Long.parseLong(pageId.toString());
						parseText = true;
					} else if (name.equals("text")) {
						if (parseText) {
							Map<String, List<String>> macroBlocks = new HashMap<>();
							String text = WikiDatabasePreparation.removeMacroBlocks(ctext.toString(), macroBlocks);
							try {
								if (!macroBlocks.isEmpty()) {
									LatLon ll = getLatLonFromGeoBlock(
											macroBlocks.get(WikivoyageTemplates.LOCATION.getType()));
									if (!ll.isZero()) {
										String filename = getFileName(macroBlocks.get(WikivoyageTemplates.BANNER.getType()));
										if (imageLinks) {
											if (!filename.isEmpty()) {
												imageStorage.saveageBanner(filename);
											}
											imageStorage.saveImageLinks(title.toString());
											return;
										}
										if (id++ % 500 == 0) {
											log.debug("Article accepted " + cid + " " + title.toString() + " " + ll.getLatitude()
													+ " " + ll.getLongitude() + " free: "
													+ (Runtime.getRuntime().freeMemory() / (1024 * 1024)));
										}
										final HTMLConverter converter = new HTMLConverter(false);
										CustomWikiModel wikiModel = new CustomWikiModel("https://upload.wikimedia.org/wikipedia/commons/${image}", 
												"https://"+lang+".wikivoyage.com/wiki/${title}", folderPath, imagePrep);
										String plainStr = wikiModel.render(converter, text);
										prep.setString(1, Encoder.encodeUrl(title.toString()));
										prep.setString(2, title.toString());
										if (uncompressed) {
											prep.setString(3, plainStr);
										} else {
											prep.setBytes(3, stringToCompressedByteArray(bous, plainStr));
										}
										// part_of
										prep.setString(4, Encoder.encodeUrl(
												parsePartOf(macroBlocks.get(WikivoyageTemplates.PART_OF.getType()))));
										
										prep.setDouble(5, ll.getLatitude());
										prep.setDouble(6, ll.getLongitude());
										// banner
										prep.setString(7, wikiModel.getImageLinkFromDB(filename));
										// gpx_gz
										if (uncompressed) {
											prep.setString(8, generateGpx(macroBlocks.get(WikivoyageTemplates.POI.getType())));
										} else {
											prep.setBytes(8, stringToCompressedByteArray(bous, 
													generateGpx(macroBlocks.get(WikivoyageTemplates.POI.getType()))));
										}
										prep.setLong(9, cid);
										addBatch();
									}
								}
							} catch (SQLException e) {
								throw new SAXException(e);
							}
						}
						ctext = null;
					}
				}
			} catch (IOException e) {
				throw new SAXException(e);
			}
		}
		
		private String generateGpx(List<String> list) {
			if (list != null && !list.isEmpty()) {
				GPXFile f = new GPXFile();
				List<WptPt> points = new ArrayList<>(); 
				for (String s : list) {
					String[] info = s.split("\\|");
					WptPt point = new WptPt();
					String category = info[0].replaceAll("\n", "");
					if (category.toLowerCase().equals("vcard")) {
						point.category = transformCategory(info);
					} else {
						point.category = category;
					}
					for (int i = 1; i < info.length; i++) {
						String field = info[i].trim();
						String value = "";
						if (field.indexOf("=") != -1) {
							value = field.substring(field.indexOf("=") + 1, field.length()).trim();
						}
						if (!value.isEmpty()) {
							try {
								String areaCode = "";
								if (field.contains("name=") || field.contains("nome=") || field.contains("nom=")) {
									point.name = value;
								} else if (field.contains("url=") || field.contains("sito=")) {
									point.link = value;
								} else if (field.contains("intl-area-code=")) {
									areaCode = value;
								} else if (field.contains("lat=") || field.contains("latitude=")) {
									point.lat = Double.valueOf(value);
								} else if (field.contains("long=") || field.contains("longitude=")) {
									point.lon = Double.valueOf(value);
								} else if (field.contains("content=") || field.contains("descrizione=") 
										|| field.contains("description")) {
									point.desc = point.desc = point.desc == null ? value : 
										point.desc + "\n" + value;
								} else if (field.contains("email=")) {
									point.desc = point.desc == null ? "Email: " + value : 
										point.desc + "\nEmail: " + value;
								} else if (field.contains("phone=") || field.contains("tel=") || field.contains("téléphone")) {
									point.desc = point.desc == null ? "Phone: " + areaCode + value : 
										point.desc + "\nPhone: " + areaCode + value;
								} else if (field.contains("price=") || field.contains("prezzo=") || field.contains("prix=")) {
									point.desc = point.desc == null ? "Price: " + value : 
										point.desc + "\nPrice: " + value;
								} else if (field.contains("hours=") || field.contains("orari=") || field.contains("horaire=")) {
									point.desc = point.desc == null ? "Working hours: " + value : 
										point.desc + "\nWorking hours: " + value;
								} else if (field.contains("directions=") || field.contains("direction=")) {
									point.desc = point.desc == null ? "Directions: " + value : 
										point.desc + "\nDirections: " + value;
								}
							} catch (Exception e) {}
						}
					}
					if (point.hasLocation() && point.name != null && !point.name.isEmpty()) {
						point.setColor();
						points.add(point);
					}
				}
				if (!points.isEmpty()) {
					f.addPoints(points);
					return GPXUtils.asString(f);
				}
			}
			return "";
		}
		
		private String transformCategory(String[] info) {
			String type = "";
			for (int i = 1; i < info.length; i++) {
				if (info[i].startsWith("type=")) {
					type = info[i] .substring(info[i].indexOf("=") + 1, info[i].length());
				}
			}
			return type;
		}
		
		private byte[] stringToCompressedByteArray(ByteArrayOutputStream baos, String toCompress) {
			baos.reset();
			try {
				GZIPOutputStream gzout = new GZIPOutputStream(baos);
				gzout.write(toCompress.getBytes("UTF-8"));
				gzout.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return baos.toByteArray();
		}

		private String getFileName(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String bannerInfo = list.get(0);
				String[] infoSplit = bannerInfo.split("\\|");
				for (String s : infoSplit) {
					String toCompare = s.toLowerCase();
					if (toCompare.contains(".jpg") || toCompare.contains(".jpeg") 
							|| toCompare.contains(".png") || toCompare.contains(".gif")) {
						s = s.replace("https:", "");
						int equalInd = s.indexOf("=");
						int columnInd = s.indexOf(":");
						int index = (equalInd == -1 && columnInd == -1) ? -1 : (columnInd > equalInd ? columnInd : equalInd);
						if (index != -1) {
							return s.substring(index + 1, s.length()).trim();
						}
						return s.trim();
					}
				}
			}
			return "";
		}
		
		private LatLon getLatLonFromGeoBlock(List<String> list) {
			double lat = 0d;
			double lon = 0d;
			if (list != null && !list.isEmpty()) {
				String location = list.get(0);
				String[] parts = location.split("\\|");
				// skip malformed location blocks
				if (location.contains("geo|")) {
					try {
						lat = Double.valueOf(parts[1]);
						lon = Double.valueOf(parts[2]);
					} catch (Exception e) {	}
				} else {
					String latStr = "";
					String lonStr = "";
					for (String part : parts) {
						part = part.replaceAll(" ", "");
						if (part.startsWith("lat=") || part.startsWith("latitude=")) {
							latStr = part.substring(part.indexOf("=") + 1, part.length()).replaceAll("\n", "");
						} else if (part.startsWith("lon=") || part.startsWith("long=") || part.startsWith("longitude=")) {
							lonStr = part.substring(part.indexOf("=") + 1, part.length()).replaceAll("\n", "");
						}
					}
					String regex = "(\\d+).+?(\\d+).+?(\\d*).*?([N|E|W|S|n|e|w|s]+)";
					if (latStr.matches(regex) && lonStr.matches(regex)) {
						lat = toDecimalDegrees(latStr, regex);
						lon = toDecimalDegrees(lonStr, regex);
					} else {
						try {
							lat = Double.valueOf(latStr.replaceAll("°", ""));
							lon = Double.valueOf(lonStr.replaceAll("°", ""));
						} catch (Exception e) {}
					}
				}
			}
			return new LatLon(lat, lon);
		}

		private double toDecimalDegrees(String str, String regex) {
			Pattern p = Pattern.compile(regex);
			Matcher m = p.matcher(str);
			m.find();
			double res = 0;
			double signe = 1.0;
			double degrees = 0;
			double minutes = 0;
			double seconds = 0;
			String hemisphereOUmeridien = "";
			try {
				degrees = Double.parseDouble(m.group(1));
		        minutes = Double.parseDouble(m.group(2));
		        seconds = m.group(3).isEmpty() ? 0 :Double.parseDouble(m.group(3));
		        hemisphereOUmeridien = m.group(4);
			} catch (Exception e) {
				// Skip malformed strings
			}
			if ((hemisphereOUmeridien.equalsIgnoreCase("W")) || (hemisphereOUmeridien.equalsIgnoreCase("S"))) {
				signe = -1.0;
			}
			res = signe * (Math.floor(degrees) + Math.floor(minutes) / 60.0 + seconds / 3600.0);
			return res;
		}

		private String parsePartOf(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String partOf = list.get(0);
				if (partOf.toLowerCase().contains("quickfooter")) {
					return parsePartOfFromQuickBar(partOf);
				} else if (partOf.toLowerCase().startsWith("footer|")) {
					String part = "";
					try {
						int index = partOf.indexOf('|', partOf.indexOf('|') + 1);
						part = partOf.substring(partOf.indexOf("=") + 1, 
								index == -1 ? partOf.length() : index);
					} catch (Exception e) {
						System.out.println("Error parsing the partof: " + partOf);
					}
					return part;
				} else {
					return partOf.substring(partOf.indexOf("|") + 1, partOf.length());
				}
			}
			return "";
		}

		private String parsePartOfFromQuickBar(String partOf) {
			String[] info = partOf.split("\\|");
			String region = "";
			for (String s : info) {
				if (s.indexOf("=") != -1) {
					if (!s.contains("livello")) {
						region = s.substring(s.indexOf("=") + 1, s.length()).trim();
					}
				}
			}
			return region;
		}
	}
}
