package com.xxwn.ticket_rush.domain.kbo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class KboScheduleClient {

    private static final String KBO_URL = "https://www.koreabaseball.com/ws/Schedule.asmx/GetMonthSchedule";
    private static final Pattern GAME_PATTERN =
            Pattern.compile("(\\S+)\\s*:\\s*(\\S+)\\s*\\[(.+?)](?:\\s*(.+))?");

    // KBO API 약칭 → 정식 팀명 (프론트 필터와 일치)
    private static final Map<String, String> TEAM_NAMES = Map.of(
            "LG",   "LG 트윈스",
            "KIA",  "KIA 타이거즈",
            "두산",  "두산 베어스",
            "SSG",  "SSG 랜더스",
            "롯데",  "롯데 자이언츠",
            "삼성",  "삼성 라이온즈",
            "NC",   "NC 다이노스",
            "한화",  "한화 이글스",
            "KT",   "KT 위즈",
            "키움",  "키움 히어로즈"
    );

    private final ObjectMapper objectMapper;

    public List<KboGameDto> fetch(int year, int month) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.set("Referer", "https://www.koreabaseball.com/");

        // srIdList 쉼표가 URL 인코딩되지 않도록 raw string으로 구성
        String rawBody = "leId=1&srIdList=0,9,6&seasonId=" + year
                + "&gameMonth=" + String.format("%02d", month) + "&teamId=0";

        List<KboGameDto> games = new ArrayList<>();
        try {
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                    KBO_URL, new HttpEntity<>(rawBody, headers), byte[].class);
            String json = new String(response.getBody(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);

            // rows = 주(week) 배열, row = 요일(day) 셀 배열
            for (JsonNode week : root.path("rows")) {
                for (JsonNode dayCell : week.path("row")) {
                    // endGame = 해당 날짜 모든 경기 종료, 스킵
                    if ("endGame".equals(dayCell.path("Class").asText(""))) continue;

                    String html = dayCell.path("Text").asText();
                    Document doc = Jsoup.parse(html);

                    // 날짜 추출 (<li class="dayNum">24</li>)
                    String dayStr = doc.select("li.dayNum").text().trim();
                    if (dayStr.isEmpty()) continue;
                    String date = String.format("%04d%02d%02d", year, month, Integer.parseInt(dayStr));

                    // 각 <li> 중 점수(<b>)가 없는 것 = 예정 경기
                    for (Element li : doc.select("li:not(.dayNum)")) {
                        if (!li.select("b").isEmpty()) continue; // 점수 있음 = 진행/종료

                        Matcher m = GAME_PATTERN.matcher(li.text().trim());
                        if (!m.find()) continue;

                        String away = fullName(m.group(1));
                        String home = fullName(m.group(2));
                        String venue = m.group(3).trim();
                        String status = m.group(4) != null ? m.group(4).trim() : "";

                        if (!status.isEmpty()) continue; // 우천취소 등

                        games.add(new KboGameDto(date, away, home, venue));
                    }
                }
            }
            log.info("KBO 일정 파싱 완료 - {}년 {}월, {}경기", year, month, games.size());
        } catch (Exception e) {
            log.warn("KBO 일정 조회 실패 year={} month={}: {}", year, month, e.getMessage());
        }
        return games;
    }

    private String fullName(String shortName) {
        return TEAM_NAMES.getOrDefault(shortName, shortName);
    }
}
