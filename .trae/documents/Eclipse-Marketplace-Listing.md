# Eclipse Marketplace Listing — ABAP AI Completion (final submission draft)

> NOTE: The direct URL `https://marketplace.eclipse.org/listing-request` is no
> longer valid (it returns 404). The Eclipse Marketplace is Drupal-based and the
> submission form only appears **after you log in**. Use the flow below to submit.

## 0. HOW TO REACH THE SUBMISSION FORM (updated)

1. Go to `https://marketplace.eclipse.org/user/login` (or
   `https://marketplace.eclipse.org/user/register` first if you have no account).
2. After logging in, use the **Listings** menu at the top of the site and choose
   **Submit a Solution / Add a Listing** to open the submission webform.
3. Fill in the form using the fields below (all copy-paste ready in this file).

> All text below is in English for direct copy-paste into the marketplace form.
> Replace the `TODO` placeholders (mainly the update-site URL, and swap in your own
> screenshots) before submitting.

---

## 1. LISTING TITLE

```
ABAP AI Completion
```

## 2. LISTING DESCRIPTION (short, shown on the tile, <= ~200 chars)

```
AI-powered code completion for SAP ABAP in Eclipse. Uses any OpenAI-compatible
LLM endpoint (local or cloud) for context-aware suggestions, resolves INCLUDE
programs, and references sibling programs and custom SKILL templates.
```
##Revision log message
Initial submission of the ABAP-AI-completion plugin.

This is an AI-powered code completion tool for SAP ABAP developers, leveraging Large Language Models (LLM) with flexible backend support, including local LLMs.

Key Features:

AI-Powered Completions: Provides context-aware ABAP code suggestions based on LLM.

Enhanced Context (Beyond Copilot): Option to include all open ABAP code from the current workspace as context, not just the active editor.

Deep Code Reference Search: Searches related programs (configurable depth, recommended 1 level) to provide richer context for suggestions.

Local Skill Directory Support: Uses local .abap, .txt, or .skill files as reference material for more relevant completions. This is utilized for manual triggering, unlike Copilot's skill configuration.

Flexible Triggers: Supports manual trigger (Ctrl+Shift+.) and optional auto-completion mode.

ABAP INCLUDE Parsing: Automatically parses INCLUDE programs for accurate context.

Floating Overlay & Customization: Displays suggestions in a Copilot-like floating overlay with customizable styles.

Flexible Backend: Compatible with any service endpoint that supports the OpenAI Chat Completions API.

Note on Completion Modes:

Manual Trigger (Ctrl+Shift+.): Uses the full context, including the Skill Directory.

Auto-Completion (requestQuickCompletion): Uses a simplified, independent prompt and does not load Skill Directory content. In Preferences, the manual trigger can be configured to show an overlay or insert directly.
## 3. LONG DESCRIPTION (HTML supported)

```html
ABAP AI Completion is an Eclipse plugin that brings AI-powered code completion
to SAP ABAP developers.

It works with any service endpoint compatible with the OpenAI Chat Completions
API, including local models (e.g. Ollama, LM Studio) as well as cloud providers
(OpenAI, Azure OpenAI, and other compatible gateways). Only your API Base URL,
model name and API key need to be configured.

<h3>Key features</h3>
<ul>
  <li><b>AI code completion</b> &ndash; press <code>Ctrl+Shift+.</code> to get
  context-aware ABAP code suggestions at the cursor.</li>
  <li><b>Auto-complete mode</b> &ndash; optional mode that automatically
  triggers AI suggestions shortly after you stop typing.</li>
  <li><b>INCLUDE resolution</b> &ndash; automatically expands relevant ABAP
  INCLUDE programs so the AI understands the full context.</li>
  <li><b>Workspace-aware context</b> &ndash; beyond the current editor, the
  plugin can pass other ABAP files open in the workspace and searched parent
  programs as reference code for better suggestions.</li>
  <li><b>Custom SKILL references</b> &ndash; define reusable templates,
  functions, or code conventions in <code>.abap</code>, <code>.txt</code> or
  <code>.skill</code> files to guide the generated code.</li>
  <li><b>Floating overlay</b> &ndash; suggestions are shown in a Copilot-style
  overlay; press <code>Tab</code> to accept.</li>
  <li><b>Customizable</b> &ndash; adjustable colors, delay, max tokens,
  temperature and system prompt.</li>
</ul>

<h3>Requirements</h3>
<ul>
  <li>Eclipse 4.7 (Neon) or later</li>
  <li>Java 17 or later (JDK / JRE)</li>
  <li>Network access to your configured AI API endpoint</li>
</ul>

The plugin is open source (MIT License). Source code and full documentation are
available in the GitHub repository.
```

## 4. VERSION

```
1.0.2
```

## 5. CATEGORY

```
Developer Tools
```

## 6. TAGS

```
ABAP, AI, Code Completion, SAP, LLM, Copilot, OpenAI
```

## 7. LICENSE

```
MIT License
```

## 8. UPDATE SITE URL (p2 repository — REQUIRED for install)

```
TODO: replace with your GitHub Pages update-site URL, e.g.
https://yan252.github.io/ABAP-AI-completion/update-site/
```

> The directory behind this URL must serve the files generated by
> `generate-update-site.ps1` (site.xml, content.jar, artifacts.jar,
> features/..., plugins/...). See the Chinese guide for how to publish it.

## 9. PROJECT / SOURCE URL

```
https://github.com/yan252/ABAP-AI-completion
```

## 10. CHANGELOG / DOCUMENTATION URL

```
https://github.com/yan252/ABAP-AI-completion/blob/main/README.md
```

## 11. SCREENSHOTS

Eclipse Marketplace accepts 1&ndash;3 screenshots (recommended 1280x800 or
wider, PNG). Provide at least:

| # | Suggested shot | Content |
|---|----------------|---------|
| 1 | Preference page | `Window > Preferences > ABAP AI Completion` showing the AI connection settings and buttons |
| 2 | Completion overlay (optional) | An ABAP editor with the Copilot-style floating suggestion overlay visible |

Place the screenshot files next to this document (e.g. `screenshots/`) and
upload them in the marketplace form. Do not use placeholders in the live
listing.

---

## METADATA SUMMARY (copy-paste table)

| Field          | Value                                                       |
|----------------|-------------------------------------------------------------|
| Title          | ABAP AI Completion                                          |
| Version        | 1.0.2                                                       |
| Category       | Developer Tools                                             |
| Update Site    | TODO: https://yan252.github.io/ABAP-AI-completion/update-site/ |
| License        | MIT License                                                 |
| Eclipse / Java | Eclipse 4.7+, Java 17+                                      |
| Tags           | ABAP, AI, Completion, SAP, LLM, Copilot, OpenAI             |
| Source         | https://github.com/yan252/ABAP-AI-completion                |

---

## PRE-SUBMISSION CHECKLIST

- [ ] GitHub repo is public and pushed (`git push origin main`).
- [ ] GitHub Pages is enabled and the update-site URL returns
      `site.xml` / `content.jar` (test in a browser).
- [ ] Update-site files are committed and included in the published branch.
- [ ] Screenshots taken and ready to upload (2 recommended).
- [ ] A `LICENSE` file (MIT) is present in the repo root (recommended).
- [ ] README documents the marketplace / update-site install path.
