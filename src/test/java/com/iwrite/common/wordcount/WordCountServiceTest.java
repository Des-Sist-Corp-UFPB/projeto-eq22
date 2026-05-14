package com.iwrite.common.wordcount;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WordCountServiceTest {

    private final WordCountService wordCountService = new WordCountService();

    @Test
    void returnsZeroForBlankText() {
        assertThat(wordCountService.countWords(null)).isZero();
        assertThat(wordCountService.countWords("")).isZero();
        assertThat(wordCountService.countWords("   ")).isZero();
    }

    @Test
    void countsSimpleText() {
        assertThat(wordCountService.countWords("uma cena curta")).isEqualTo(3);
    }

    @Test
    void ignoresPunctuationAroundWords() {
        assertThat(wordCountService.countWords("Olá, mundo! Tudo bem?")).isEqualTo(4);
    }

    @Test
    void countsAccentedWords() {
        assertThat(wordCountService.countWords("Coração, ação e memória")).isEqualTo(4);
    }
}
