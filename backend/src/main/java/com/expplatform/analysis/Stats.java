package com.expplatform.analysis;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.special.Erf;
import org.apache.commons.math3.special.Gamma;

/** Statistical helpers used by the design calculator and the analysis engine. */
public final class Stats {

    private static final NormalDistribution STD_NORMAL = new NormalDistribution(null, 0, 1);

    private Stats() {}

    public static double zQuantile(double p) {
        return STD_NORMAL.inverseCumulativeProbability(p);
    }

    /** Two-sided p-value for a z statistic (erfc keeps precision for very small p). */
    public static double twoSidedP(double z) {
        if (Double.isNaN(z)) return 1.0;
        return Erf.erfc(Math.abs(z) / Math.sqrt(2.0));
    }

    /** Upper-tail chi-square probability. */
    public static double chiSquareP(double chi2, int df) {
        if (df <= 0 || chi2 <= 0) return 1.0;
        return Gamma.regularizedGammaQ(df / 2.0, chi2 / 2.0);
    }

    /** Users per arm for a two-proportion test (normal approximation). */
    public static int sampleSizePerArm(double baseline, double relMde, double alpha, double power) {
        double p1 = baseline;
        double p2 = Math.min(0.999, baseline * (1 + relMde));
        double za = zQuantile(1 - alpha / 2);
        double zb = zQuantile(power);
        double num = Math.pow(za + zb, 2) * (p1 * (1 - p1) + p2 * (1 - p2));
        return (int) Math.ceil(num / Math.pow(p2 - p1, 2));
    }

    /** Pearson chi-square test of independence; columns with zero total are ignored. Returns {chi2, df, p}. */
    public static double[] contingency(long[][] table) {
        int rows = table.length;
        if (rows < 2) return new double[]{0, 0, 1};
        int cols = table[0].length;
        long[] rowSum = new long[rows];
        long[] colSum = new long[cols];
        long total = 0;
        for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) {
            rowSum[i] += table[i][j]; colSum[j] += table[i][j]; total += table[i][j];
        }
        if (total == 0) return new double[]{0, 0, 1};
        int usedCols = 0;
        for (long c : colSum) if (c > 0) usedCols++;
        int usedRows = 0;
        for (long r : rowSum) if (r > 0) usedRows++;
        double chi2 = 0;
        for (int i = 0; i < rows; i++) {
            if (rowSum[i] == 0) continue;
            for (int j = 0; j < cols; j++) {
                if (colSum[j] == 0) continue;
                double e = (double) rowSum[i] * colSum[j] / total;
                chi2 += Math.pow(table[i][j] - e, 2) / e;
            }
        }
        int df = (usedRows - 1) * (usedCols - 1);
        return new double[]{chi2, df, chiSquareP(chi2, df)};
    }

    public static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
