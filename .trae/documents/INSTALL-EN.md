# ABAP AI Completion - Installation & Usage (English)

Installation instructions for end users obtained via the Eclipse Marketplace or
a hosted update site.

## Requirements

- Eclipse 4.7 (Neon) or later
- Java 17 or later
- Network access to your configured AI API endpoint

## Install via Eclipse Marketplace

1. In Eclipse, select `Help` > `Eclipse Marketplace...`
2. In the search box, type: `ABAP AI Completion`
3. Click **Install** on the result, then follow the wizard.
4. Restart Eclipse when prompted.

## Install via Update Site (alternative)

1. Select `Help` > `Install New Software...`
2. Click **Add...**
3. Set:
   - Name: `ABAP AI Completion`
   - Location: `https://<your-host>/update-site/`
4. Check **ABAP AI Completion Feature** and click **Next** and **Finish**.
5. Restart Eclipse.

## Getting Started

After installation, open the preference page:

- `Window` > `Preferences` > `ABAP AI Completion`

Configure at minimum:

- **API Base URL** - your OpenAI-compatible endpoint, e.g. `https://api.openai.com/v1`
- **Model Name** - e.g. `gpt-4`, `deepseek-v4-flash`
- **API Key** - your authentication key

Then open any ABAP source file (`.abap`, `.prog`) and press `Ctrl+Shift+.` to
trigger AI code completion. Suggestions appear in a floating overlay; press
`Tab` to accept.

Optional: enable **Auto-complete while typing** to trigger suggestions
automatically after you stop typing (default delay 2000 ms).

## Troubleshooting

- Confirm Eclipse is 4.7+ and Java is 17+.
- Verify API Base URL, Model Name and API Key.
- Use **Test Connection** on the preference page.
- Increase `Max Tokens` for longer completions.

## License

MIT License. See the project repository for details.
