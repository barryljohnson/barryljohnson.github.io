import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/* ─────────────────────────────────────────────────────────────────────────────
   Song
───────────────────────────────────────────────────────────────────────────── */
class Song {

    public final String artist;
    public final String year;
    public final String album;
    public final String track;
    public final String title;
    public final String artFile;

	public static final UNKNOWN = "";

    public Song(String artist, String year, String album, String track, String title, String artFile) {
        this.artist  = artist;
        this.year    = year;
        this.album   = album;
        this.track   = track;
        this.title   = title;
        this.artFile = artFile;
    }

    public static Song fromPath(Path file, ArtExtractor artExtractor) {
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String base = (dot > 0) ? filename.substring(0, dot) : filename;
        String[] parts = base.split(" - ", 5);

        if (parts.length != 5) {
            System.out.println("Warning: unexpected filename format: " + filename);
            return new Song(UNKNOWN, UNKNOWN, base, UNKNOWN, base, artExtractor.extractToFile(file, UNKNOWN, base));
        }

        return new Song(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim(), parts[4].trim(), artExtractor.extractToFile(file, artist, album));
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        
		sb.append("{\"title\":\"").append(JsonUtil.escape(title)).append("\"");
        sb.append(",\"artist\":\"").append(JsonUtil.escape(artist)).append("\"");
        sb.append(",\"year\":\"").append(JsonUtil.escape(year)).append("\"");
        sb.append(",\"album\":\"").append(JsonUtil.escape(album)).append("\"");
        sb.append(",\"track\":\"").append(JsonUtil.escape(track)).append("\"");
        if (artFile != null) 
			sb.append(",\"art\":\"").append(JsonUtil.escape(artFile)).append("\"");
        sb.append("}");
        
		return sb.toString();
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   TrackValidator
───────────────────────────────────────────────────────────────────────────── */
class TrackValidator {

    private final Map<String, Set<Integer>> albumTracks = new ConcurrentHashMap<>();
    private final Map<String, String> albumArtist = new ConcurrentHashMap<>();

    public void record(String artist, String album, String track) {

        if (track == null || track.isEmpty()) 
			return;
		
        try {
            int num = Integer.parseInt(track.trim());
            String key = album.trim().toLowerCase();
            albumTracks.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(num);
            albumArtist.putIfAbsent(key, artist);
        } 
		catch (NumberFormatException e) {
        }
    }

    public void report() {
        boolean anyIssue = false;
        List<String> keys = new ArrayList<>(albumTracks.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            Set<Integer> tracks = albumTracks.get(key);
            List<Integer> nums = new ArrayList<>(tracks);
            Collections.sort(nums);

            if (!nums.isEmpty()) {
				int min = nums.get(0);
				int max = nums.get(nums.size() - 1);

				List<Integer> missing = new ArrayList<>();
				for (int i = min; i <= max; i++) {
					if (!nums.contains(i)) 
						missing.add(i);
				}

				if (!missing.isEmpty()) {
					String artist = albumArtist.getOrDefault(key, "");
					System.out.println("MISSING TRACKS: missing track(s) " + missing + "\n\t\t\t" + (artist.isEmpty() ? "" : artist) + " " + key + " flac");
					anyIssue = true;
				}
			}
        }

        if (!anyIssue) System.out.println("Track validation: no missing tracks detected.");
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   ArtExtractor
───────────────────────────────────────────────────────────────────────────── */
class ArtExtractor {

    public static final String COVERS_DIR = "covers";
    private final Set<String> saved = ConcurrentHashMap.newKeySet();

    public ArtExtractor() {}

    public String extractToFile(Path mp3, String artist, String album) {

        if (!mp3.getFileName().toString().toLowerCase().endsWith(".mp3")) 
			return null;

        String rawKey  = (artist.isEmpty() ? album : artist + " - " + album).trim();
        String safeKey = sanitize(rawKey);
        String filename = safeKey + ".jpg";
        Path dest = Paths.get(COVERS_DIR, filename);

        if (saved.contains(safeKey)) 
			return filename;

        if (Files.exists(dest)) {
            saved.add(safeKey);
            return filename;
        }

        try {
            org.jaudiotagger.audio.AudioFile af = AudioFileIO.read(mp3.toFile());
			Tag tag = af.getTag();
            
			if (tag == null) 
				return null;
			
            Artwork artwork = tag.getFirstArtwork();
            
			if (artwork == null) 
				return null;
            
			byte[] data = artwork.getBinaryData();
            
			if (data == null || data.length == 0) 
				return null;
            
			Files.write(dest, data);
            saved.add(safeKey);
			
            return filename;
        } catch (Exception e) {
            System.out.println("Could not read art from: " + mp3.getFileName());
            return null;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|%#&+@]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .trim();
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   JsonStreamWriter
───────────────────────────────────────────────────────────────────────────── */
class JsonStreamWriter implements AutoCloseable {

    private final java.io.FileOutputStream stream;
    private final AtomicBoolean hasEntry = new AtomicBoolean(false);

    public JsonStreamWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.stream = new java.io.FileOutputStream(file.toFile());
        writeRaw("[\n");
    }

    public void write(String jsonObject) throws IOException {
        synchronized (stream) {
            if (hasEntry.getAndSet(true)) writeRaw(",\n");
            writeRaw("  " + jsonObject);
        }
    }

    private void writeRaw(String s) throws IOException {
        stream.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        synchronized (stream) {
            writeRaw("\n]");
            stream.close();
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   SongIndex
───────────────────────────────────────────────────────────────────────────── */
class SongIndex implements AutoCloseable {

    private final String outputDir;
    private final Map<String, JsonStreamWriter> writers = new ConcurrentHashMap<>();

    public SongIndex(String outputDir) throws IOException {
        this.outputDir = outputDir;
        Files.createDirectories(Paths.get(outputDir));
    }

    public void preopen(String key) { 
		getOrOpen(key);
	}

    public void write(String key, Song song) throws IOException {
        getOrOpen(key).write(song.toJson());
    }

    private JsonStreamWriter getOrOpen(String key) {
        return writers.computeIfAbsent(key, k -> {
            try { return new JsonStreamWriter(Paths.get(outputDir, k + ".json")); }
            catch (IOException e) { throw new RuntimeException("Failed to open writer for: " + k, e); }
        });
    }

    public Set<String> keys() { return writers.keySet(); }

    @Override
    public void close() throws IOException {
        for (JsonStreamWriter w : writers.values()) w.close();
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   CategoryDetector
───────────────────────────────────────────────────────────────────────────── */
class CategoryDetector {

	private static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put("!ost-tribute-va", "OST");
        RULES.put("!christmas",      "Christmas");
        RULES.put("!comedy",         "Comedy");
        RULES.put("!halloween",      "Halloween");
        RULES.put("lessthan320bps",  "Other");
        RULES.put("mp3_alternate",   "Alternate");
    }

	public static String detect(String relativePath) {
        String p = relativePath.toLowerCase();
        for (Map.Entry<String, String> rule : RULES.entrySet()) {
            if (p.contains(rule.getKey())) return rule.getValue(); 
        }
        return null;
    }

    public static Set<String> allCategories() {
        return new LinkedHashSet<>(RULES.values());
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   JsonUtil
───────────────────────────────────────────────────────────────────────────── */
class JsonUtil {
    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   FileScanner
───────────────────────────────────────────────────────────────────────────── */
class FileScanner {

    private static final int THREAD_COUNT = 4;
    private final ExecutorService pool     = Executors.newFixedThreadPool(THREAD_COUNT);
    private final AtomicLong      completed = new AtomicLong(0);
    private final List<Future<?>> futures   = Collections.synchronizedList(new ArrayList<>());

    public void submit(Runnable task) {
        futures.add(pool.submit(() -> {
            try { task.run(); }
            finally {
                long done = completed.incrementAndGet();
                if (done % 10000 == 0) System.out.println("  Processed " + done + " files...");
            }
        }));
    }

    public void awaitCompletion() throws InterruptedException, ExecutionException {
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        System.out.println("  Total processed: " + completed.get() + " files.");
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   LibraryIndexer
───────────────────────────────────────────────────────────────────────────── */
public class LibraryIndexer {

    static final String LETTER_DIR   = "data/titles";
    static final String PREFIX_DIR   = "data/prefix";
    static final String CATEGORY_DIR = "data/categories";

    public static void main(String[] args) throws Exception {

        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);

        if (args.length == 0) {
            System.out.println("Usage: java LibraryIndexer <root_directory> [--only-alternate]");
            return;
        }

        Path root = Paths.get(args[0]);
        boolean onlyAlternate = false;

        for (int i = 1; i < args.length; i++) {
            if ("--only-alternate".equalsIgnoreCase(args[i])) {
                onlyAlternate = true;
            }
        }

        String buildNumber = nextBuildNumber();
        System.out.println("Starting build " + buildNumber + (onlyAlternate ? " (Mode: Alternate Only)" : ""));

        ArtExtractor   artExtractor   = new ArtExtractor();
        TrackValidator trackValidator = new TrackValidator();

        Files.createDirectories(Paths.get(LETTER_DIR));
        Files.createDirectories(Paths.get(PREFIX_DIR));
        Files.createDirectories(Paths.get(CATEGORY_DIR));
        Files.createDirectories(Paths.get(ArtExtractor.COVERS_DIR));

        try (SongIndex letterIndex   = new SongIndex(LETTER_DIR);
             SongIndex categoryIndex = new SongIndex(CATEGORY_DIR);
             SongIndex prefixIndex   = new SongIndex(PREFIX_DIR)) {

            for (char c = 'A'; c <= 'Z'; c++) letterIndex.preopen(String.valueOf(c));
            letterIndex.preopen("num");
            for (String cat : CategoryDetector.allCategories()) categoryIndex.preopen(cat);

            FileScanner scanner = new FileScanner();

            System.out.println("Scanning: " + root);

            boolean finalOnlyAlternate = onlyAlternate;

            // Updated walk logic to skip directories starting with [S] or [SKIP]
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString().toUpperCase();
                    if (name.startsWith("[S]") || name.startsWith("[SKIP]")) {
                        System.out.println("Skipping directory: " + dir.getFileName());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().toLowerCase().endsWith(".mp3")) {
                        scanner.submit(() -> processFile(root, file, artExtractor, trackValidator,
                                letterIndex, categoryIndex, prefixIndex, finalOnlyAlternate));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.err.println("Could not access: " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });

            System.out.println("Waiting for processing to complete...");
            scanner.awaitCompletion();

            System.out.println("Validating tracks...");
            trackValidator.report();

            System.out.println("Writing search-index.json...");
            writeSearchIndex(prefixIndex.keys(), buildNumber);
        }

        System.out.println("Done. Build: " + buildNumber);
    }

    static void processFile(Path root, Path file,
                             ArtExtractor artExtractor,
                             TrackValidator trackValidator,
                             SongIndex letterIndex,
                             SongIndex categoryIndex,
                             SongIndex prefixIndex,
                             boolean onlyAlternate) {
        try {
            String category = CategoryDetector.detect(root.relativize(file).toString());

            if (onlyAlternate && !"Alternate".equals(category)) {
                return;
            }

            Song song = Song.fromPath(file, artExtractor);
            if (song.title.isEmpty()) return;

            if (!song.track.isEmpty()) trackValidator.record(song.artist, song.album, song.track);

            if (category != null) {
                categoryIndex.write(category, song);
            } else {
                String sortSource = (song.artist == null || song.artist.isEmpty()) ? song.title : song.artist;
                letterIndex.write(letterKey(sortSource), song);
            }

            Optional<String> prefix = prefixKey(song.title);
            if (prefix.isPresent()) prefixIndex.write(prefix.get(), song);

        } catch (Exception e) {
            System.out.println("Error processing: " + file.getFileName() + " - " + e.getMessage());
        }
    }

    static String letterKey(String text) {
        if (text == null || text.trim().isEmpty()) return "num";
        
        String cleanText = text.trim().toUpperCase();
        
        if (cleanText.startsWith("THE ")) {
            cleanText = cleanText.substring(4);
        }
        
        if (cleanText.isEmpty()) return "num";
        
        char first = cleanText.charAt(0);
        return Character.isLetter(first) ? String.valueOf(first) : "num";
    }

    static Optional<String> prefixKey(String title) {
        if (title.length() < 2) return Optional.empty();
        char first = title.charAt(0);
        if (!Character.isLetter(first)) return Optional.empty();
        return Optional.of(title.substring(0, 2).toLowerCase());
    }

    static String nextBuildNumber() {
        try {
            Path p = Paths.get("data/search-index.json");
            if (Files.exists(p)) {
                String content = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"buildNumber\"\\s*:\\s*\"(\\d+)\\.(\\d+)\\.(\\d+)\"")
                        .matcher(content);
                if (m.find()) {
                    int major = Integer.parseInt(m.group(1));
                    int minor = Integer.parseInt(m.group(2));
                    int patch = Integer.parseInt(m.group(3));
                    return major + "." + minor + "." + (patch + 1);
                }
            }
        } catch (Exception e) { }
        return "1.0.0";
    }

    static void writeSearchIndex(Set<String> prefixes, String buildNumber) throws IOException {
        List<String> keys = new ArrayList<>(prefixes);
        Collections.sort(keys);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream("data/search-index.json")) {
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            out.write("{\n".getBytes(utf8));
            out.write(("  \"buildNumber\":\"" + buildNumber + "\",\n").getBytes(utf8));
            for (int i = 0; i < keys.size(); i++) {
                String line = "  \"" + keys.get(i) + "\":1";
                if (i < keys.size() - 1) line += ",";
                line += "\n";
                out.write(line.getBytes(utf8));
            }
            out.write("}".getBytes(utf8));
        }
    }
}