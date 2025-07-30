package utils;

import model.EvaluationResult;
import model.JavaMethod;
import model.Release;
import model.Ticket;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class PrintUtils {

    private static final Logger LOGGER = Logger.getLogger(PrintUtils.class.getName());
    private static final String DELIMITER = "\n";

    // Define base directories for different types of output
    private static final String REPORT_DIR = "reportFiles/";
    private static final String WEKA_RESULTS_DIR = "WekaResults/";
    private static final String SLASH = "/";

    /**
     * Ensures that a directory exists. If not, it creates it.
     * @param directoryPath The path of the directory to check/create.
     * @throws IOException if the directory cannot be created.
     */
    private static void ensureDirectoryExists(String directoryPath) throws IOException {
        File dir = new File(directoryPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory: " + directoryPath);
        }
    }

    public static void printCommits(String project, List<RevCommit> commitList, String name) throws IOException {
        String projectDir = REPORT_DIR + project.toLowerCase() + SLASH;
        ensureDirectoryExists(projectDir);

        try (FileWriter fileWriter = new FileWriter(projectDir + name)) {
            fileWriter.append("id,committer,creationDate\n");
            for (RevCommit commit : commitList) {
                fileWriter.append(commit.getName()).append(",")
                        .append(commit.getCommitterIdent().getName()).append(",")
                        .append(String.valueOf(LocalDate.parse(new SimpleDateFormat("yyyy-MM-dd").format(commit.getCommitterIdent().getWhen()))))
                        .append(DELIMITER);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing commits file.", e);
        }
    }

    public static void printTickets(String project, List<Ticket> ticketList) throws IOException {
        String projectDir = REPORT_DIR + project.toLowerCase() + SLASH;
        ensureDirectoryExists(projectDir);

        try (FileWriter fileWriter = new FileWriter(projectDir + "AllTickets.csv")) {
            fileWriter.append("key,creationDate,resolutionDate,injectedVersion,openingVersion,fixedVersion,affectedVersion\n");

            ticketList.sort(Comparator.comparing(Ticket::getCreationDate));
            for (Ticket ticket : ticketList) {
                List<String> avNames = new ArrayList<>();
                for(Release release : ticket.getAv()) {
                    avNames.add(release.getName());
                }
                fileWriter.append(ticket.getId()).append(",")
                        .append(String.valueOf(ticket.getCreationDate())).append(",")
                        .append(String.valueOf(ticket.getResolutionDate())).append(",")
                        .append(ticket.getIv().getName()).append(",")
                        .append(ticket.getOv().getName()).append(",")
                        .append(ticket.getFv().getName()).append(",")
                        .append(String.join(";", avNames)) // Use a different separator for lists inside a cell
                        .append(DELIMITER);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing tickets file.", e);
        }
    }

    public static void printReleases(String project, List<Release> releaseList, String name) throws IOException {
        String projectDir = REPORT_DIR + project.toLowerCase() + SLASH;
        ensureDirectoryExists(projectDir);

        try (FileWriter fileWriter = new FileWriter(projectDir + name)) {
            fileWriter.append("id,releaseName,releaseDate,numOfCommits\n");

            for (Release release : releaseList) {
                fileWriter.append(String.valueOf(release.getId())).append(",")
                        .append(release.getName()).append(",")
                        .append(String.valueOf(release.getDate())).append(",")
                        .append(String.valueOf(release.getCommitList().size()))
                        .append(DELIMITER);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing releases file.", e);
        }
    }

    public static void printMethods(String project, List<JavaMethod> methods) throws IOException {
        String projectDir = REPORT_DIR + project.toLowerCase() + SLASH;
        ensureDirectoryExists(projectDir);

        try (FileWriter fileWriter = new FileWriter(projectDir + "Methods.csv")) {
            fileWriter.append("fullyQualifiedName,firstCommit,#Commits\n");

            for (JavaMethod m : methods) {
                String firstCommit = m.getCommits().isEmpty() ? "" : m.getCommits().get(0).toString();

                fileWriter.append(escapeCSV(m.getFullyQualifiedName())).append(",")
                        .append(escapeCSV(firstCommit)).append(",")
                        .append(String.valueOf(m.getCommits().size()))
                        .append(DELIMITER);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing methods file.", e);
        }
    }


    // Creates a dataset CSV file from a list of JavaMethod objects
    public static void createDataset(String fullPath, List<JavaMethod> methods) {
        try (FileWriter fileWriter = new FileWriter(fullPath)) {
            fileWriter.append("Release,LOC,#Parameters,#Authors,#Revisions,StmtAdded,StmtDeleted,MaxChurn,AvgChurn,#Branches,NestingDepth,Buggy\n");

            for (JavaMethod m : methods) {
                fileWriter.append(String.valueOf(m.getRelease().getId())).append(",")
                        .append(String.valueOf(m.getLoc())).append(",")
                        .append(String.valueOf(m.getNumParameters())).append(",")
                        .append(String.valueOf(m.getNumAuthors())).append(",")
                        .append(String.valueOf(m.getNumRevisions())).append(",")
                        .append(String.valueOf(m.getTotalStmtAdded())).append(",")
                        .append(String.valueOf(m.getTotalStmtDeleted())).append(",")
                        .append(String.valueOf(m.getMaxChurnInARevision())).append(",")
                        .append(String.valueOf(m.getAvgChurn())).append(",")
                        .append(String.valueOf(m.getNumberOfBranches())).append(",")
                        .append(String.valueOf(m.getNestingDepth())).append(",")
                        .append(m.isBuggy() ? "yes" : "no")
                        .append(DELIMITER);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error creating dataset at " + fullPath, e);
        }
    }


    // Writes the evaluation results of the WEKA classifiers
    public static void printEvaluationResults(String project, List<EvaluationResult> results) throws IOException {
        String projectDir = WEKA_RESULTS_DIR + project.toLowerCase() + SLASH;
        ensureDirectoryExists(projectDir);

        String filename = projectDir + project.toUpperCase() + "_EvaluationResults.csv";

        try (FileWriter writer = new FileWriter(filename)) {
            // Write the header
            writer.append(EvaluationResult.getCsvHeader());
            writer.append("\n");

            // Write each result row
            for (EvaluationResult result : results) {
                writer.append(result.toCsvString());
                writer.append("\n");
            }

            LOGGER.log(Level.INFO, "WEKA evaluation results saved to: {0}", filename);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error writing WEKA evaluation results file.", e);
        }
    }


    private static String escapeCSV(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            // Replace any existing double quotes with two double quotes
            field = field.replace("\"", "\"\"");
            // Wrap the entire field in double quotes
            return "\"" + field + "\"";
        }
        return field;
    }
}