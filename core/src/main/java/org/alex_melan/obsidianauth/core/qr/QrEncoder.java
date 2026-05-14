package org.alex_melan.obsidianauth.core.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/**
 * Encodes a string (the {@code otpauth://...} URI) into a 128×128 ZXing {@link BitMatrix}.
 *
 * <p>CPU-bound — callers wrap on the {@code AsyncExecutor}.
 *
 * <p>Error-correction level {@code M} (≈15% redundancy) gives reliable scanning under
 * realistic phone-screen-against-server-screen conditions; quiet-zone margin {@code 2}
 * leaves enough breathing room on the 128-pixel canvas to scan from a few inches away.
 */
public final class QrEncoder {

    public static final int DEFAULT_SIZE_PIXELS = 128;
    private static final int DEFAULT_MARGIN = 2;

    private QrEncoder() {
        // static-only
    }

    public static BitMatrix encode(String content) {
        return encode(content, DEFAULT_SIZE_PIXELS, ErrorCorrectionLevel.M, DEFAULT_MARGIN);
    }

    public static BitMatrix encode(String content, int size,
                                   ErrorCorrectionLevel ecc, int margin) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ecc);
        hints.put(EncodeHintType.MARGIN, margin);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed for content of length " + content.length(), e);
        }
    }
}
