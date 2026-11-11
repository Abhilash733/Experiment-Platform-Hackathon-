package com.expplatform.generator;

import com.expplatform.repository.Values;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure in-memory tests of the generator (no database needed). */
class SyntheticDataGeneratorTest {

    private static String hash(Dataset ds) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, List<Object[]>> e : ds.tables.entrySet()) {
            md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            for (Object[] row : e.getValue()) {
                for (Object v : row) md.update((Values.text(v) + "|").getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    @Test
    void sameSeedProducesIdenticalDataset() throws Exception {
        String a = hash(new SyntheticDataGenerator().generate(20261022L, 5000, 10));
        String b = hash(new SyntheticDataGenerator().generate(20261022L, 5000, 10));
        String c = hash(new SyntheticDataGenerator().generate(7L, 5000, 10));
        assertEquals(a, b, "same seed must reproduce byte-identical data");
        assertNotEquals(a, c, "a different seed must change the data");
    }

    @Test
    void bucketsAreRoughlyUniform() {
        int[] counts = new int[10];
        for (int i = 1; i <= 100_000; i++) counts[SyntheticDataGenerator.bucket(20261022L, "EXP-2026-000001", String.format("USR-%06d", i)) / 1000]++;
        for (int c : counts) assertTrue(Math.abs(c - 10_000) < 450, "decile count " + c);
    }

    /** Simulated arm rates must be unbiased: the average estimated lift over seeds is close to the documented true ITT lift. */
    @Test
    void delayedConversionScenarioIsUnbiasedAcrossSeeds() {
        double sumLift = 0;
        int seeds = 8;
        for (long seed = 1; seed <= seeds; seed++) {
            Dataset ds = new SyntheticDataGenerator().generate(seed, 50_000, 10);
            String exp = "EXP-2026-000006";
            Map<String, String> asgVariant = new java.util.HashMap<>();
            Map<String, LocalDateTime> asgTime = new java.util.HashMap<>();
            long[] n = new long[2];
            for (Object[] r : ds.tables.get("assignment")) {
                if (!exp.equals(r[1])) continue;
                asgVariant.put((String) r[0], (String) r[3]);
                asgTime.put((String) r[0], (LocalDateTime) r[5]);
                n[((String) r[3]).endsWith("-A") ? 0 : 1]++;
            }
            java.util.Set<String> converted = new java.util.HashSet<>();
            long[] x = new long[2];
            for (Object[] r : ds.tables.get("conversion_event")) {
                if (!exp.equals(r[2]) || !"SUBSCRIPTION".equals(r[4])) continue;
                String asg = (String) r[1];
                LocalDateTime at = asgTime.get(asg);
                LocalDateTime occ = (LocalDateTime) r[5];
                if (occ.isBefore(at.plusDays(14)) && converted.add(asg)) x[asgVariant.get(asg).endsWith("-A") ? 0 : 1]++;
            }
            double lift = ((double) x[1] / n[1]) / ((double) x[0] / n[0]) - 1;
            System.out.printf("seed %d: control %.4f treatment %.4f lift %.3f%n", seed, (double) x[0] / n[0], (double) x[1] / n[1], lift);
            sumLift += lift;
        }
        double truth = SyntheticDataGenerator.trueIttLift(ScenarioCatalog.SCENARIOS.get(5), ScenarioCatalog.SCENARIOS.get(5).arms().get(1));
        System.out.printf("mean lift %.3f vs true ITT lift %.3f%n", sumLift / seeds, truth);
        assertTrue(Math.abs(sumLift / seeds - truth) < 0.08, "estimator should be unbiased across seeds");
    }
}
