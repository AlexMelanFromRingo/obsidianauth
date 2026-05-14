package org.alex_melan.obsidianauth.core.qr;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QrEncoderTest {

    @Test
    void encodeThenDecode_recoversOriginalContent() throws Exception {
        String content = "otpauth://totp/ExampleNet:alice?secret=JBSWY3DPEHPK3PXP&issuer=ExampleNet&digits=6&period=30&algorithm=SHA1";

        BitMatrix matrix = QrEncoder.encode(content);
        assertThat(matrix.getWidth()).isEqualTo(128);
        assertThat(matrix.getHeight()).isEqualTo(128);

        Result decoded = decodeQr(matrix);
        assertThat(decoded.getText()).isEqualTo(content);
        assertThat(decoded.getBarcodeFormat()).isEqualTo(BarcodeFormat.QR_CODE);
    }

    @Test
    void encode_deterministicForSameInput() {
        String content = "otpauth://totp/Test:user?secret=ABCDEF&issuer=Test&digits=6&period=30";
        BitMatrix m1 = QrEncoder.encode(content);
        BitMatrix m2 = QrEncoder.encode(content);
        // Compare both matrices module-by-module — they should be identical.
        for (int x = 0; x < m1.getWidth(); x++) {
            for (int y = 0; y < m1.getHeight(); y++) {
                assertThat(m1.get(x, y)).isEqualTo(m2.get(x, y));
            }
        }
    }

    private static Result decodeQr(BitMatrix matrix) throws Exception {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(bitmap, hints);
    }
}
