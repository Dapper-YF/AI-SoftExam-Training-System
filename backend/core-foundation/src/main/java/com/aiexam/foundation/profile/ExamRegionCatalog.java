package com.aiexam.foundation.profile;

import java.util.List;
import java.util.Map;

public final class ExamRegionCatalog {
  private static final Map<String, String> REGIONS = Map.ofEntries(
      Map.entry("BJ", "北京市"), Map.entry("TJ", "天津市"), Map.entry("HE", "河北省"),
      Map.entry("SX", "山西省"), Map.entry("NM", "内蒙古自治区"), Map.entry("LN", "辽宁省"),
      Map.entry("JL", "吉林省"), Map.entry("HL", "黑龙江省"), Map.entry("SH", "上海市"),
      Map.entry("JS", "江苏省"), Map.entry("ZJ", "浙江省"), Map.entry("AH", "安徽省"),
      Map.entry("FJ", "福建省"), Map.entry("JX", "江西省"), Map.entry("SD", "山东省"),
      Map.entry("HA", "河南省"), Map.entry("HB", "湖北省"), Map.entry("HN", "湖南省"),
      Map.entry("GD", "广东省"), Map.entry("GX", "广西壮族自治区"), Map.entry("HI", "海南省"),
      Map.entry("CQ", "重庆市"), Map.entry("SC", "四川省"), Map.entry("GZ", "贵州省"),
      Map.entry("YN", "云南省"), Map.entry("XZ", "西藏自治区"), Map.entry("SN", "陕西省"),
      Map.entry("GS", "甘肃省"), Map.entry("QH", "青海省"), Map.entry("NX", "宁夏回族自治区"),
      Map.entry("XJ", "新疆维吾尔自治区"));
  private ExamRegionCatalog() {}
  public static String requireName(String code) {
    String name = REGIONS.get(code == null ? "" : code.trim().toUpperCase());
    if (name == null) throw new IllegalArgumentException("unsupported exam region");
    return name;
  }
  public static List<Region> all() { return REGIONS.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new Region(e.getKey(), e.getValue())).toList(); }
  public record Region(String code, String name) {}
}
