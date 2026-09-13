package fr.tracerag.rag;

public interface EmbeddingProvider {
    String name();

    double[] embed(String text);

    default double similarity(double[] left, double[] right) {
        int size = Math.min(left.length, right.length);
        double product = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < size; index++) {
            product += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return product / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}

