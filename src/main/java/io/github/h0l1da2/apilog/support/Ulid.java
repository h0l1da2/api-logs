package io.github.h0l1da2.apilog.support;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 기록 id로 쓰는 ULID(26자, Crockford Base32)를 만든다.
 *
 * 설계 의도:
 * - 앞 10자가 밀리초 시각이라 문자열 정렬 = 시간순 정렬이고, id만 보고 어느 날짜 파일에 있는지 알 수 있다
 * - 외부 라이브러리 없이 쓰려고 직접 구현했다. 보안용 난수가 아니므로 ThreadLocalRandom을 쓴다
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 26;
    private static final int TIME_LENGTH = 10;

    private Ulid() {
    }

    public static String generate(long epochMillis) {
        final char[] out = new char[LENGTH];
        long time = epochMillis;
        for (int i = TIME_LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = TIME_LENGTH; i < LENGTH; i++) {
            out[i] = ALPHABET[random.nextInt(32)];
        }
        return new String(out);
    }

    /** id 앞 10자에서 밀리초 시각을 꺼낸다. 형식이 틀리면 IllegalArgumentException. */
    public static long timestamp(String ulid) {
        if (ulid == null || ulid.length() != LENGTH) {
            throw new IllegalArgumentException("ULID 형식이 아닙니다: " + ulid);
        }
        long time = 0;
        for (int i = 0; i < TIME_LENGTH; i++) {
            final int value = decode(ulid.charAt(i));
            time = (time << 5) | value;
        }
        return time;
    }

    private static int decode(char c) {
        final char upper = Character.toUpperCase(c);
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == upper) {
                return i;
            }
        }
        throw new IllegalArgumentException("ULID 문자가 아닙니다: " + c);
    }
}
