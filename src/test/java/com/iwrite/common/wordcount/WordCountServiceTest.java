package com.iwrite.common.wordcount;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WordCountServiceTest {

    private final WordCountService wordCountService = new WordCountService();

    @Test
    void returnsZeroForNullText() {
        assertThat(wordCountService.countWords(null)).isZero();
    }

    @Test
    void returnsZeroForEmptyText() {
        assertThat(wordCountService.countWords("")).isZero();
    }

    @Test
    void returnsZeroForWhitespaceOnlyText() {
        assertThat(wordCountService.countWords("   \n\t   ")).isZero();
    }

    @Test
    void countsSimpleText() {
        assertThat(wordCountService.countWords("uma cena curta")).isEqualTo(3);
    }

    @Test
    void ignoresPunctuationAroundWords() {
        assertThat(wordCountService.countWords("Ola, mundo! Tudo bem?")).isEqualTo(4);
    }

    @Test
    void countsAccentedWords() {
        assertThat(wordCountService.countWords("Coração, ação e memória")).isEqualTo(4);
    }

    @Test
    void countsWordsAcrossLineBreaksAndRepeatedSpaces() {
        assertThat(wordCountService.countWords("primeira linha\n\nsegunda\tlinha   final")).isEqualTo(5);
    }

    @Test
    void countsNumbersAsWords() {
        assertThat(wordCountService.countWords("Capitulo 12 tem 3 cenas")).isEqualTo(5);
    }

    @Test
    void countsMixedTextWithSymbols() {
        assertThat(wordCountService.countWords("Cena #42: alfa-beta custa R$ 10,00 + revisao_final")).isEqualTo(9);
    }
}
