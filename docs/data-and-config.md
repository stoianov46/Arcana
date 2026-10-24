# Data, configuration and vault conventions

## Where things live

| What | Location | Format | Owner |
|---|---|---|---|
| Settings | `~/.config/arcana/config.json` (or `$ARCANA_CONFIG`) | JSON (`AppConfig`) | `ConfigStore` |
| Chats | `~/.config/arcana/chats/<id>.json` | `StoredChat` JSON (images not stored) | `ChatStore` |
| Sessions | `~/.config/arcana/sessions/<id>.json` | `StoredSession` JSON, written as events happen | `SessionStore` |
| Token usage | `~/.config/arcana/usage.jsonl` | one `UsageRecord` per line | `UsageStore` |
| Tool backups | `~/.config/arcana/backups/` | copies of replaced/edited files | `ToolBox` |
| Your notes | the vault folder (`vaultPath`) | Markdown | `Vault` |
| Vault trash | `<vault>/.trash/` | Obsidian-compatible trash | `Vault.trash/replace`, inbox backups |
| Attachments | `<vault>/<outputFolder>/attachments/` | copied pictures/files | `Vault.copyAttachment` |

The config directory is derived from `ConfigStore.defaultPath().parent`; tests and diagnostics redirect everything by setting
`ARCANA_CONFIG` to another path.

## `AppConfig` fields

| Field | Default | Meaning |
|---|---|---|
| `ollamaUrl` | `http://localhost:11434` | Ollama base URL |
| `model` | `gemma4:latest` | model for every feature |
| `temperature` | 0.7 | sampling temperature |
| `timeoutSeconds` | 300 | HTTP timeout per request |
| `vaultPath` | `$ARCANA_VAULT` or empty | Obsidian vault folder (`~` allowed) |
| `outputFolder` | `Arcana` | vault folder for notes Arcana creates (empty = vault root) |
| `contextNotes` / `contextCharsPerNote` | 5 / 4000 | how many notes / chars `/vault` mode sends |
| `systemPrompt` / `summaryPrompt` | see source | prompts editable in Settings |
| `theme` | `dark` | theme id (`Themes.all`) |
| `uiScale` | `normal` | `compact`/`normal`/`spacious` layout density |
| `navPosition` | `left` | `left` / `right` / `top` |
| `sidebarCollapsed` | false | icon rail |
| `tabOrder` | `[]` | screen names in the user's order (first = start screen) |
| `animateLogo` | true | colour sweep while generating |
| `plainGlyphs` | false | ASCII-only output (screen readers) |
| `renderMarkdown` | true | rich Markdown vs raw text |
| `showThinking` | true | "Gemma is thinking…" status |
| `chatHistory` | 20 | latest messages sent as context |
| `keepChats` / `chatsKept` | true / 50 | persist chats / how many to keep |
| `chatUseVault` | false | answer from vault (also `/vault`) |
| `confirmLeaveChat` | true | ask before leaving an unsaved chat |
| `copyAs` | `markdown` | select-mode copy format: `markdown` / `plain` |
| `inboxFolder` | `Inbox` | where unprocessed notes are |
| `dailyFolder` / `dailyFormat` | `""` / `YYYY-MM-DD` | daily note fallback when Obsidian has no setting |
| `staleDays` | 90 | stale threshold |
| `lastPickerDir` | `""` | last folder of the file browser |
| `toolsEnabled` | false | tools in Chat |
| `toolFolders` | `""` | extra folders for tools (`;`-separated) |
| `toolAutoRead` | true | read-only tools run without asking |
| `toolSteps` | 8 | max tool rounds per question |

Adding a field: give it a default (old config files must still load), add a Settings row (see
[conventions.md](conventions.md)), document it here.

## Vault conventions Arcana relies on

- Notes are `*.md`; paths are vault-relative with `/`. Folders starting with `.` are ignored by listings and the index.
- Links: `[[Note title]]`, `[[folder/Note]]`, `[[Note|alias]]`, `[[Note#heading]]` (resolved by path or title; unresolved ones count as
  broken links). Embeds/links to non-note files (`![[pic.png]]`, `[[x.pdf]]`) are neither links nor broken links.
  Links and tags inside code fences/inline code are ignored.
- Tags: `#tag` inline and `tags:` in frontmatter (list `[a, b]` or YAML `- a` lines).
- Frontmatter written by Arcana: `created`, `generator: arcana`, `model`, `tags: [arcana]`. `Vault.replace` and
  `mergeFrontmatterTags` preserve existing frontmatter.
- Daily notes: path from `.obsidian/daily-notes.json` (`folder`, `format`) else settings. Capture appends `- HH:mm text`.
- Deletion is never permanent: `Vault.trash` → `.trash/`. Replacements/inbox processing back up originals into `.trash/`.
- Session summary notes and saved chats are normal notes created in `outputFolder`.

## Data shapes (persisted JSON)

- `StoredChat { id, title, updated, model, messages: [ChatMessage] }`; `ChatMessage` fields as in
  [core-module.md](core-module.md) (images omitted on disk; `files`, `toolCalls`, `toolName` kept).
- `StoredSession { id, started, ended?, savedAs?, events: [ { time, kind, text, path? } ] }`; `kind` ∈ `SessionKind`
  (`asked, task, created, appended, replaced, deleted, saved, captured, processed, attached, tool, session`).
- `UsageRecord { time, feature, model, promptTokens, completionTokens, millis }`; `feature` ∈ `Chat`, `Generate` (AI notes), `Inbox`, `Session`
