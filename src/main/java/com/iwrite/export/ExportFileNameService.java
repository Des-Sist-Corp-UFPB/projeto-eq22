package com.iwrite.export;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Service
public class ExportFileNameService {

    public String fileName(String title, String fallbackSlug, String extension) {
        String slug = slugify(title);
        if (slug.isBlank()) {
            slug = fallbackSlug;
        }

        return slug + "." + extension;
    }

    private String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
