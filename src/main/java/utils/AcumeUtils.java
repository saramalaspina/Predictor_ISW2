package utils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

import model.PredictionResult;

public class AcumeUtils {
    public static void exportToAcumeCsv(String filePath, List<PredictionResult> predictions) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.println("ID,Size,Predicted,Actual");
            for (int i = 0; i < predictions.size(); i++) {
                PredictionResult p = predictions.get(i);
                String actual = p.isBuggy ? "YES" : "NO";
                writer.printf(Locale.US, "%d,%d,%.6f,%s%n", i, p.loc, p.probability, actual);
            }
        }
    }
}