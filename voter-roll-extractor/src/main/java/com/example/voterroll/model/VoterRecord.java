package com.example.voterroll.model;

/** One voter card from the roll. Every field is a String so nothing is lost on odd values. */
public record VoterRecord(
        String serialNo,
        String epicNo,
        String name,
        String relationType,
        String relationName,
        String houseNumber,
        String age,
        String gender,
        String section,
        String partNo,
        String assemblyConstituency,
        int page,
        String sourceFile,
        String extractedBy,
        String remarks) {

    public int serialAsInt() {
        try {
            return Integer.parseInt(serialNo);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
