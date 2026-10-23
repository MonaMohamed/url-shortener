package com.urlshortener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShortCodeGeneratorTest {

    private ShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ShortCodeGenerator();
        ReflectionTestUtils.setField(generator, "defaultLength", 7);
    }

    @Test
    void generate_returnsCodeWithDefaultLength() {
        String code = generator.generate();
        assertEquals(7, code.length());
    }

    @Test
    void generate_returnsAlphanumericCharactersOnly() {
        String code = generator.generate();
        assertTrue(code.matches("^[a-zA-Z0-9]+$"), "Code should be alphanumeric: " + code);
    }

    @Test
    void generate_withCustomLength_returnsCorrectLength() {
        assertEquals(3, generator.generate(3).length());
        assertEquals(10, generator.generate(10).length());
        assertEquals(1, generator.generate(1).length());
    }

    @Test
    void generate_producesDifferentCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(generator.generate());
        }
        assertTrue(codes.size() > 90, "Expected most codes to be unique, got " + codes.size() + " unique out of 100");
    }

    @Test
    void generate_customLength_returnsAlphanumericOnly() {
        for (int i = 0; i < 50; i++) {
            String code = generator.generate(12);
            assertTrue(code.matches("^[a-zA-Z0-9]+$"), "Code should be alphanumeric: " + code);
        }
    }
}
