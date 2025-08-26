package controller;

import utils.PrintUtils;

import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StartPredictor {
    private static final Logger LOGGER = Logger.getLogger(StartPredictor.class.getName());

    public static void main(String[] args) {
        Logger.getLogger("").setLevel(Level.INFO);

        Scanner scanner = new Scanner(System.in);

        // Scegli il progetto
        PrintUtils.printOption("Select the project to analyze:");
        PrintUtils.printOption("1: BOOKKEEPER");
        PrintUtils.printOption("2: OPENJPA");
        PrintUtils.printOption("Enter your choice: ");
        String projectChoice = scanner.nextLine();
        String project = "1".equals(projectChoice) ? "BOOKKEEPER" : "OPENJPA";

        PrintUtils.printOption("-------------------------------------------");
        PrintUtils.printOption("Project selected: " + project.toUpperCase());
        PrintUtils.printOption("-------------------------------------------");

        PipelineController controller = new PipelineController(project);

        // Loop del menu principale
        boolean exit = false;
        while (!exit) {
            PrintUtils.printOption("\nSelect the analysis to perform:");
            PrintUtils.printOption("1: [Milestone 1] Execute Data Extraction and Dataset Creation");
            PrintUtils.printOption("2: [Step 2] Execute WEKA Classifier Analysis");
            PrintUtils.printOption("3: Execute Correlation Analysis");
            PrintUtils.printOption("4: Execute Refactoring Analysis");
            PrintUtils.printOption("5: Execute What-If Analysis");
            PrintUtils.printOption("0: Exit");
            PrintUtils.printOption("Enter your choice: ");

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
                        controller.executeRefactoringAnalysis();
                        break;
                    case "5":
                        controller.executeWhatIfAnalysis();
                        break;
                    case "0":
                        exit = true;
                        break;
                    default:
                        PrintUtils.printOption("Invalid choice. Please try again.");
                        break;
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "An error occurred during the analysis", e);
            }
        }

        PrintUtils.printOption("Exiting application");
        scanner.close();
    }
}
