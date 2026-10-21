package com.example.redislab.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModulesServiceTest {

    @Test
    void buildsSearchQueriesFromFilters() {
        assertThat(ModulesService.buildQuery("", null, null)).isEqualTo("*");
        assertThat(ModulesService.buildQuery("Leather chair", "Office", 50.0))
                .isEqualTo("(leather* chair*) @category:{Office} @price:[0 50.0]");
    }

    @Test
    void stripsQuerySyntaxFromUserText() {
        assertThat(ModulesService.cleanText("@name:{x} | -foo*")).isEqualTo("name x foo");
        assertThat(ModulesService.buildQuery("x", "Off}ice|", null)).isEqualTo("(x) @category:{Office}");
    }

    @Test
    void convertsBetweenHexAndHsl() {
        double[] hsl = ModulesService.hexToHsl(ModulesService.hslToHex(210, 0.85, 0.5));
        assertThat(hsl[0]).isCloseTo(210, within(1.0));
        assertThat(hsl[1]).isCloseTo(0.85, within(0.01));
        assertThat(hsl[2]).isCloseTo(0.5, within(0.01));
    }

    @Test
    void embeddingPutsNeighbouringHuesClose() {
        double[] red = ModulesService.embed(355, 0.8, 0.5);
        double[] alsoRed = ModulesService.embed(5, 0.8, 0.5);
        double[] cyan = ModulesService.embed(180, 0.8, 0.5);
        assertThat(cosine(red, alsoRed)).isGreaterThan(0.95);
        assertThat(cosine(red, cyan)).isLessThan(0.0);
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }
}
