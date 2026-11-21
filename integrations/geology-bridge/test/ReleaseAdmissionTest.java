package com.orbital.geoworld.geology;

public final class ReleaseAdmissionTest {
    public static void main(String[] args) {
        String expected = System.getProperty("geoworld.geology.test-expected", "pass");
        try {
            ReleaseAdmission.requireConfigured();
            if (!"pass".equals(expected)) throw new AssertionError("invalid release was admitted");
            System.out.println("PASS: pinned release admitted before world open");
        } catch (IllegalStateException error) {
            if (!"fail".equals(expected)) throw error;
            System.out.println("PASS: mismatched release rejected before world open");
        }
    }
}
