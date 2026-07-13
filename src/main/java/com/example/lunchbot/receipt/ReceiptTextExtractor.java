package com.example.lunchbot.receipt;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Достаёт текст из чека.
 *
 * Принимаются ТОЛЬКО PDF. Фото и скриншоты отклоняются: OCR убран целиком,
 * чтобы не грузить ноутбук (нативный tesseract через JNA — 1–3 сек и +200 МБ на чек).
 * PDFBox читает текстовый слой за десятки миллисекунд, в пределах heap.
 *
 * Все банки (MBank, Demir, Эльдик, Bakai, O!Деньги) отдают PDF с текстовым слоем —
 * кнопка «Скачать чек» в приложении, а не скриншот.
 */
@Slf4j
@Component
public class ReceiptTextExtractor {

    /** Проверяемое исключение: фото прислали вместо PDF, либо PDF пустой. */
    public static class NotPdfException extends Exception {
        public NotPdfException(String message) {
            super(message);
        }
    }

    public String extract(byte[] bytes, String fileName) throws Exception {
        boolean isPdf = bytes.length > 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';

        if (!isPdf) {
            throw new NotPdfException("Не PDF: " + fileName);
        }

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            if (text == null || text.replaceAll("\\s", "").length() < 20) {
                throw new NotPdfException("PDF без текстового слоя: " + fileName);
            }
            return text;
        }
    }
}
