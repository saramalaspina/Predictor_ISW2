package model;

public class CorrelationResult implements Comparable<CorrelationResult> {
    private final String attributeName;
    private final double correlationValue;

    public CorrelationResult(String attributeName, double correlationValue) {
        this.attributeName = attributeName;
        this.correlationValue = correlationValue;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public double getCorrelationValue() {
        return correlationValue;
    }

    @Override
    public int compareTo(CorrelationResult other) {
        // Ordina in base al valore assoluto della correlazione, in modo decrescente
        return Double.compare(Math.abs(other.correlationValue), Math.abs(this.correlationValue));
    }

    @Override
    public String toString() {
        return String.format("%-20s: % .4f", attributeName, correlationValue);
    }
}
