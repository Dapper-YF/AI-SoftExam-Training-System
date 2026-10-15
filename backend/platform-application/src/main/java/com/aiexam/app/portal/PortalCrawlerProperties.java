package com.aiexam.app.portal;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aiexam.portal.crawler")
public class PortalCrawlerProperties {
  private boolean enabled = true;
  private String cron = "0 10 0 * * *";
  private String zoneId = "Asia/Shanghai";
  private String listUrl = "https://www.ruankao.org.cn/";
  private String allowedHost = "www.ruankao.org.cn";
  private List<String> sourceUrls = new ArrayList<>(List.of(
      "https://www.ruankao.org.cn/",
      "https://rsks.gd.gov.cn/",
      "https://ks.rst.gansu.gov.cn/ncms/index.shtml",
      "https://rsj.beijing.gov.cn/ywsite/bjpta/"));
  private List<String> allowedHosts = new ArrayList<>(List.of(
      "www.ruankao.org.cn", "rsks.gd.gov.cn", "ks.rst.gansu.gov.cn", "rsj.beijing.gov.cn"));
  private int initialLookbackDays = 365;
  private int maxItemsPerRun = 160;
  private long requestDelayMillis = 350;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getCron() { return cron; }
  public void setCron(String cron) { this.cron = cron; }
  public String getZoneId() { return zoneId; }
  public void setZoneId(String zoneId) { this.zoneId = zoneId; }
  public String getListUrl() { return listUrl; }
  public void setListUrl(String listUrl) { this.listUrl = listUrl; }
  public String getAllowedHost() { return allowedHost; }
  public void setAllowedHost(String allowedHost) { this.allowedHost = allowedHost; }
  public List<String> getSourceUrls() { return sourceUrls; }
  public void setSourceUrls(List<String> sourceUrls) { this.sourceUrls = sourceUrls; }
  public List<String> getAllowedHosts() { return allowedHosts; }
  public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
  public int getInitialLookbackDays() { return initialLookbackDays; }
  public void setInitialLookbackDays(int initialLookbackDays) { this.initialLookbackDays = initialLookbackDays; }
  public int getMaxItemsPerRun() { return maxItemsPerRun; }
  public void setMaxItemsPerRun(int maxItemsPerRun) { this.maxItemsPerRun = maxItemsPerRun; }
  public long getRequestDelayMillis() { return requestDelayMillis; }
  public void setRequestDelayMillis(long requestDelayMillis) { this.requestDelayMillis = requestDelayMillis; }
}
