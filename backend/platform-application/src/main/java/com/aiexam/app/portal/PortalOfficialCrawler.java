package com.aiexam.app.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PortalOfficialCrawler {
  private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[^0-9]{1,3}(\\d{1,2})[^0-9]{1,3}(\\d{1,2})");
  private static final Pattern RESOURCE_TITLE_PATTERN = Pattern.compile("大纲|教材|考试用书|样题|考试文件|模拟练习|考试指南|考试安排|政策法规|考试计划|考试政策");
  private static final Pattern NEWS_TITLE_PATTERN = Pattern.compile("报名|缴费|准考证|资格审核|报名通知|通知|通告|公告|成绩|合格|证书|考试安排|考试|政策");
  private static final Pattern IRRELEVANT_TITLE_PATTERN = Pattern.compile("事业单位|招聘岗位|招聘公告|招聘");
  private static final Pattern SOFT_EXAM_TITLE_PATTERN = Pattern.compile("计算机|软件|软考|信息系统|系统架构|系统分析|项目管理师|网络工程|信息技术");
  private final PortalCrawlerProperties properties;
  private final HttpClient httpClient;

  @Autowired
  public PortalOfficialCrawler(PortalCrawlerProperties properties) {
    this(properties, buildHttpClient());
  }

  PortalOfficialCrawler(PortalCrawlerProperties properties, HttpClient httpClient) {
    this.properties = properties;
    this.httpClient = httpClient;
  }

  private static HttpClient buildHttpClient() {
    try {
      KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
      windowsRoot.load(null, null);
      TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(windowsRoot);
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustManagers.getTrustManagers(), null);
      return HttpClient.newBuilder().sslContext(sslContext).connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL).build();
    } catch (Exception ignored) {
      return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL).build();
    }
  }

  public List<PortalCrawlCandidate> fetchSince(Instant cutoff) throws IOException, InterruptedException {
    List<PortalCrawlCandidate> result = new ArrayList<>();
    List<String> sources = properties.getSourceUrls() == null || properties.getSourceUrls().isEmpty()
        ? List.of(properties.getListUrl()) : properties.getSourceUrls();
    for (String source : sources) {
      if (result.size() >= properties.getMaxItemsPerRun()) break;
      try {
        result.addAll(fetchSource(source, cutoff, properties.getMaxItemsPerRun() - result.size()));
      } catch (IOException exception) {
        // 区域站点可能临时不可用，继续采集其他官方来源。
      }
    }
    return result.stream().sorted(Comparator.comparing(PortalCrawlCandidate::publishedAt).reversed()).toList();
  }

  private List<PortalCrawlCandidate> fetchSource(String source, Instant cutoff, int limit)
      throws IOException, InterruptedException {
    URI listUri = allowedUri(source);
    Map<String, String> links = extractLinks(requestText(listUri), listUri);
    if ("www.ruankao.org.cn".equalsIgnoreCase(listUri.getHost())) {
      links.putIfAbsent("https://www.ruankao.org.cn/book/main.html", "考试用书");
      links.putIfAbsent("https://www.ruankao.org.cn/exam/plan.html", "考试计划");
      links.putIfAbsent("https://www.ruankao.org.cn/exam/policy.html", "考试政策");
      links.putIfAbsent("https://www.ruankao.org.cn/guide/main.html", "考试指南");
    }
    List<PortalCrawlCandidate> result = new ArrayList<>();
    Instant targetedCutoff = cutoff.minus(Duration.ofDays(1825));
    for (Map.Entry<String, String> link : links.entrySet().stream()
        .sorted(Comparator.comparing((Map.Entry<String, String> item) -> !item.getKey().contains("/article/content/"))).toList()) {
      if (result.size() >= limit) break;
      PortalCrawlCandidate candidate;
      try {
        candidate = parseDetail(request(URI.create(link.getKey())), link.getKey(), link.getValue());
      } catch (IOException exception) {
        continue;
      }
      if (candidate.title().isBlank()) continue;
      if (IRRELEVANT_TITLE_PATTERN.matcher(candidate.title()).find()) continue;
      if (!"www.ruankao.org.cn".equalsIgnoreCase(listUri.getHost())
          && !SOFT_EXAM_TITLE_PATTERN.matcher(candidate.title()).find()) continue;
      boolean recent = !candidate.publishedAt().isBefore(cutoff);
      boolean targetedHistory = !candidate.publishedAt().isBefore(targetedCutoff)
          && (RESOURCE_TITLE_PATTERN.matcher(candidate.title()).find() || NEWS_TITLE_PATTERN.matcher(candidate.title()).find());
      if (recent || targetedHistory) result.add(candidate);
      if (properties.getRequestDelayMillis() > 0) Thread.sleep(properties.getRequestDelayMillis());
    }
    return result;
  }

  Map<String, String> extractLinks(String html, URI baseUri) {
    Document document = Jsoup.parse(html, baseUri.toString());
    Map<String, String> links = new LinkedHashMap<>();
    for (Element anchor : document.select("a[href]")) {
      String title = clean(anchor.text());
      String href = anchor.absUrl("href");
      if (href.isBlank() || (title.length() < 8 && !href.contains("/article/content/"))) continue;
      try {
        URI uri = allowedUri(href);
        if (!uri.getPath().equals("/") && !uri.getPath().isBlank()) links.putIfAbsent(uri.toString(), title);
      } catch (IllegalArgumentException ignored) {
        // 导航链接和第三方链接不属于官方公告。
      }
    }
    return links;
  }

  PortalCrawlCandidate parseDetail(String html, String sourceUrl, String fallbackTitle) {
    Document document = Jsoup.parse(html, sourceUrl);
    Element heading = document.selectFirst("h1");
    String title = clean(heading == null ? "" : heading.text());
    if (title.isBlank()) title = clean(document.title());
    if (title.isBlank() || title.equals("中国计算机技术职业资格网")) title = clean(fallbackTitle);
    Element content = document.selectFirst("article, .article, .content, .news-content");
    String text = clean((content == null ? document.body().text() : content.text()));
    Instant publishedAt = parsePublishedAt(text + " " + document.text());
    String summary = text.length() <= 220 ? text : text.substring(0, 220).trim() + "...";
    if (summary.isBlank()) summary = title;
    return new PortalCrawlCandidate(title, summary, allowedUri(sourceUrl).toString(), publishedAt);
  }

  PortalCrawlCandidate parseDetail(FetchedContent fetched, String sourceUrl, String fallbackTitle) throws IOException {
    if (!fetched.isPdf()) {
      return parseDetail(new String(fetched.body(), StandardCharsets.UTF_8), sourceUrl, fallbackTitle);
    }
    String text;
    try (PDDocument document = Loader.loadPDF(fetched.body())) {
      text = clean(new PDFTextStripper().getText(document));
    }
    String summary = text.isBlank()
        ? "该官方 PDF 暂未提取到可读文本，请通过原文链接查看。"
        : text.substring(0, Math.min(text.length(), 220)).trim() + (text.length() > 220 ? "..." : "");
    return new PortalCrawlCandidate(clean(fallbackTitle), summary, allowedUri(sourceUrl).toString(), parsePublishedAt(text));
  }

  private String requestText(URI uri) throws IOException, InterruptedException {
    FetchedContent fetched = request(uri);
    if (fetched.isPdf()) throw new IOException("Expected HTML but received PDF");
    return new String(fetched.body(), StandardCharsets.UTF_8);
  }

  private FetchedContent request(URI uri) throws IOException, InterruptedException {
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "AIExamPortalBot/1.0 (+portal content synchronizer)")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          return new FetchedContent(
              response.body(),
              response.headers().firstValue("Content-Type").orElse(""));
        }
        lastFailure = new IOException("Official portal returned HTTP " + response.statusCode());
      } catch (IOException exception) {
        lastFailure = exception;
      }
    }
    throw lastFailure == null ? new IOException("Official portal request failed") : lastFailure;
  }

  private URI allowedUri(String value) {
    URI uri = URI.create(value).normalize();
    List<String> hosts = properties.getAllowedHosts() == null || properties.getAllowedHosts().isEmpty()
        ? List.of(properties.getAllowedHost()) : properties.getAllowedHosts();
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
        || hosts.stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))) {
      throw new IllegalArgumentException("URL is outside the configured official portal sources");
    }
    return uri;
  }

  private Instant parsePublishedAt(String text) {
    Matcher matcher = DATE_PATTERN.matcher(text);
    if (!matcher.find()) return Instant.now();
    LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    return date.atStartOfDay(ZoneId.of(properties.getZoneId())).toInstant();
  }

  private static String clean(String value) {
    return value.replaceAll("\\s+", " ").trim();
  }

  record FetchedContent(byte[] body, String contentType) {
    boolean isPdf() {
      return contentType.toLowerCase().contains("application/pdf")
          || (body.length >= 4 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F');
    }
  }
}
