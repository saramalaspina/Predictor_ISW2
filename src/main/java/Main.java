import controller.PipelineController;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    public static void main(String[] args) {
        // Imposta il logging una sola volta
        Logger.getLogger("").setLevel(Level.SEVERE);

        Scanner scanner = new Scanner(System.in);

        // Scegli il progetto
        System.out.println("Select the project to analyze:");
        System.out.println("1: BOOKKEEPER");
        System.out.println("2: OPENJPA");
        System.out.print("Enter your choice: ");
        String projectChoice = scanner.nextLine();
        String project = "1".equals(projectChoice) ? "BOOKKEEPER" : "OPENJPA";

        System.out.println("-------------------------------------------");
        System.out.println("Project selected: " + project.toUpperCase());
        System.out.println("-------------------------------------------");

        PipelineController controller = new PipelineController(project);

        // Loop del menu principale
        boolean exit = false;
        while (!exit) {
            System.out.println("\nSelect the analysis to perform:");
            System.out.println("1: [Milestone 1] Execute Data Extraction and Dataset Creation");
            System.out.println("2: [Step 2] Execute WEKA Classifier Analysis");
            System.out.println("3: Execute Correlation Analysis");
            System.out.println("4: Execute What-If Analysis");
            System.out.println("0: Exit");
            System.out.print("Enter your choice: ");

            String choice = scanner.nextLine();

            try {
                switch (choice) {
                    case "1":
                        controller.executeDataExtraction();
                        break;
                    case "2":
                        controller.executeClassifierAnalysis();
                        break;
                    case "3":
                        controller.executeCorrelationAnalysis();
                        break;
                    case "4":
                        controller.executeWhatIfAnalysis();
                        break;
                    case "0":
                        exit = true;
                        break;
                    default:
                        System.out.println("Invalid choice. Please try again.");
                        break;
                }
            } catch (Exception e) {
                System.err.println("\nAn error occurred during the analysis:");
                e.printStackTrace();
            }
        }

        System.out.println("Exiting application");
        scanner.close();
    }
}