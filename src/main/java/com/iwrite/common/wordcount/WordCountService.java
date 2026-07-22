package com.iwrite.common.wordcount;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WordCountService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+(?:['-][\\p{L}\\p{N}]+)?");

    public int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        Matcher matcher = WORD_PATTERN.matcher(text);
        int count = 0;

        while (matcher.find()) {
            count++;
        }

        return count;
    }
}
