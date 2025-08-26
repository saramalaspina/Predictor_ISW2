package utils;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import model.JavaMethod;
import model.Release;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GitUtils {

    private static final Logger LOGGER = Logger.getLogger(GitUtils.class.getName());

    private GitUtils() {}

    public static Release getReleaseOfCommit(RevCommit commit, List<Release> releaseList) {

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));
        LocalDate lowerBoundDate = LocalDate.parse(formatter.format(new Date(0)));
        for (Release release : releaseList) {
            LocalDate dateOfRelease = release.getDate();
            if (commitDate.isAfter(lowerBoundDate) && !commitDate.isAfter(dateOfRelease)) {
                return release;
            }
            lowerBoundDate = dateOfRelease;
        }
        return null;
    }

    public static List<DiffEntry> getDiffEntries(RevCommit parent, RevCommit commit, Repository repository) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
            diffFormatter.setContext(0); // Nessuna linea di contesto, solo le differenze
            return diffFormatter.scan(parent.getTree(), commit.getTree());
        }
    }

    public static Map<String, MethodDeclaration> parseMethods(String content) {
        Map<String, MethodDeclaration> methods = new HashMap<>();
        if (content == null || content.isEmpty()) return methods;
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            cu.findAll(MethodDeclaration.class).forEach(md -> methods.put(JavaMethod.getSignature(md), md));
        } catch (ParseProblemException | StackOverflowError ignored) {
            // Ignored
        }
        return methods;
    }

    public static Map<String, String> getFileContents(RevCommit commit, List<DiffEntry> diffs, boolean useOldPath, Repository repository) throws IOException {
        Map<String, String> contents = new HashMap<>();
        try (ObjectReader reader = repository.newObjectReader()) {
            for (DiffEntry diff : diffs) {
                String path = useOldPath ? diff.getOldPath() : diff.getNewPath();
                ObjectId id = useOldPath ? diff.getOldId().toObjectId() : diff.getNewId().toObjectId();

                if (DiffEntry.DEV_NULL.equals(path)) continue; // Skip /dev/null

                try {
                    ObjectLoader loader = reader.open(id);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    loader.copyTo(output);
                    contents.put(path, output.toString());
                } catch (org.eclipse.jgit.errors.MissingObjectException e) {
                    LOGGER.log(Level.INFO, "--- Missing object {0} for path {1} in commit {2} ---", new Object[]{id, path, commit.getName()});
                }
            }
        }
        return contents;
    }

    public static String calculateBodyHash(MethodDeclaration md) throws PipelineExecutionException {
        if (md == null) return null;
        String normalizedBody = normalizeMethodBody(md);
        if (normalizedBody.isEmpty()) return "EMPTY_BODY_HASH"; // O un altro placeholder
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(normalizedBody.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encodedhash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new PipelineExecutionException("SHA-256 Hashing error", e);
        }
    }

    public static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static String normalizeMethodBody(MethodDeclaration md) {
        if (md == null || md.getBody().isEmpty()) {
            return "";
        }

        String body = md.getBody().get().toString();
        body = body.replaceAll("//.*|/\\*(?s).*?\\*/", ""); // Remove comments
        body = body.replaceAll("\\s+", " "); // Replace multiple blank space with a single one
        return body.trim();
    }

}
