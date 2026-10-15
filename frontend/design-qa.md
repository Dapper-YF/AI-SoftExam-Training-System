# Public Home Image-to-Code Design QA

- Source visual truth: `C:\Users\Dapper\.codex\worktrees\7b49\AI高项培训系统\.runtime\public-home-image-to-code-20260719\approved-public-home-reference.png`
- Source SHA-256: `6040015957C74BCC82D4E2F6E71998BC064ED7C169F889513A0A8205BCA5331F`
- Implementation URL: `http://127.0.0.1:5174/`
- Implementation screenshot: `C:\Users\Dapper\.codex\worktrees\7b49\AI高项培训系统\.runtime\public-home-image-to-code-20260719\public-home-viewport-1440x1024-browser.png`
- Viewport and state: browser `innerWidth=1440`, `innerHeight=1024`; anonymous public home; real qualification and subject APIs loaded
- Full-view comparison: `C:\Users\Dapper\.codex\worktrees\7b49\AI高项培训系统\.runtime\public-home-image-to-code-20260719\comparison-reference-vs-implementation-viewport.png`
- Focused top comparison: `C:\Users\Dapper\.codex\worktrees\7b49\AI高项培训系统\.runtime\public-home-image-to-code-20260719\comparison-focus-header-main.png`
- Focused lower comparison: `C:\Users\Dapper\.codex\worktrees\7b49\AI高项培训系统\.runtime\public-home-image-to-code-20260719\comparison-focus-lower-sections.png`
- Responsive evidence: `public-home-viewport-1366x768-browser.png` and `public-home-viewport-390x844-browser.png` in the same ignored evidence directory

## Findings

No actionable P0, P1, or P2 visual differences remain.

- P3, brand mark: the approved reference uses a generated white swirl mark. The implementation uses the existing icon library and project name instead of copying or hand-drawing the source mark.
- P3, schedule content: the reference contains concrete countdown digits and dates. The implementation keeps the same right-card hierarchy and blue four-stage treatment but says that timing follows official announcements; this is an intentional product-safety constraint because no trustworthy current date source is available.
- P3, anonymous plan: the reference contains numeric personal progress. The implementation retains the card position, hierarchy, icon and primary action but uses a login prompt with no invented progress numbers.
- P3, qualification labels: homepage entries and grouping are supplied by the real public API. The implementation therefore does not copy generated HOT/NEW labels or misclassify API qualifications merely to match reference copy.

## Fidelity Surfaces

- Fonts and typography: the existing Microsoft YaHei/PingFang SC stack matches the reference's compact Chinese sans-serif character. Heading weights, 12-17px UI hierarchy, line height, truncation and mobile wrapping were inspected at all three viewports.
- Spacing and layout rhythm: the 56px deep-blue header, 12px page gaps, 288px/elastic/368px desktop grid, six-pixel radii, fine gray borders, compact rows, quick-start panel and lower capability strip reproduce the reference composition. At 1366 the three columns remain intact. At 390 the page becomes a menu plus one-column section flow without horizontal overflow.
- Colors and tokens: the existing indigo/cobalt/teal/orange project palette maps to the reference's blue brand system. Surfaces remain white on a cool-gray canvas with restrained shadows and no decorative gradients or blobs.
- Image and icon quality: the target is UI-led and does not require copied course art, teacher photos, QR codes or advertising imagery. All visible product icons come from the installed Element Plus icon set; no handcrafted SVG, CSS illustration, emoji or third-party brand asset was introduced.
- Copy and content: qualification and public catalog labels are real API data. News, resources and schedule entries are typed `DEMO` fixtures and visibly labelled as demonstrations. No price, teacher, download count, official source claim, live countdown, inventory total or fake personal statistic is shown.

## Interaction And Runtime Evidence

- Mobile menu opened and reported `aria-expanded=true`.
- The public “免费题库” link navigated to `/practice?mode=DAILY` under the shared top-navigation shell.
- The public catalog had no `.platform-sidebar`, no horizontal overflow and loaded real subjects/catalog data.
- Clicking the real “综合知识 / 专项刷题” action redirected the anonymous user to login while preserving the intended practice recovery target.
- Public home, qualification browser and public catalog remained anonymous; attempts, reports, mistake review and admin routes retained auth guards and dedicated workspaces.
- Browser console check at 1440x1024, 1366x768 and 390x844 returned no errors or warnings.
- Browser metrics confirmed `scrollWidth == clientWidth` at all three viewports.

## Comparison History

1. P2: all 20 real qualifications were initially expanded on the homepage, making the left card too tall and pushing the capability strip below the reference position. Fixed by showing API-backed homepage excerpts of 5 advanced, 6 intermediate and 4 primary entries while preserving all 20 on `/qualifications`.
2. P2: the first mobile pass placed qualification entries in one column, delaying the news area until the bottom of the first viewport. Fixed with a two-column mobile qualification list and safe text truncation; news now begins within the 390x844 first viewport.
3. P2: an extra “资格分类 / 真实公开目录” row shifted the advanced qualification heading below the reference position. Fixed by retaining source provenance as screen-reader text only.
4. Post-fix evidence: the final full-view and focused comparison images show aligned header height, desktop column proportions, section order, blue quick-start panel, right-side timeline hierarchy, lower-card rhythm and mobile single-flow behavior.

## Follow-up Polish

- If an approved production brand asset is provided later, it can replace the library mark without changing header geometry.
- When authoritative schedule and signed-in plan APIs are available, the compliance placeholders can be replaced without changing card layout.

final result: passed
