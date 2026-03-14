# Code Formatting

This project uses [Spotless](https://github.com/diffplug/spotless) with the Eclipse JDT formatter
to enforce consistent code style across all editors.

## Rules at a glance

- Tabs for indentation
- K&R braces (opening brace on same line)
- No spaces inside parentheses
- Spaces around operators and ternary
- 120 character line limit
- One blank line at start of class body, none at start of method bodies
- One blank line at end of class body
- One blank line between methods
- `} else {` on same line
- Optional braces — `if (x) return;`, `if (x)\n\treturn;`, or `if (x) { ... }` all valid
- Import order: `java` → `javax` → `org` → `com` → everything else
- Unused imports removed automatically

## Applying formatting

```bash
mvn spotless:apply
```

Run this before pushing. The CI check will block your PR if formatting is wrong.

## Checking without applying

```bash
mvn spotless:check
```

## Setting up the pre-commit hook (recommended)

This automatically formats your code before every commit so you never have to think about it.

```bash
pip install pre-commit   # or: brew install pre-commit
pre-commit install
```

After this, every `git commit` will run `mvn spotless:apply` automatically.

## IntelliJ IDEA

The `.idea/codeStyles/` files are committed to the repo. IntelliJ will pick them up automatically
when you open the project — no manual setup needed. Use **Code → Reformat File** (`Ctrl+Alt+L`)
to format the current file.

## VSCode / Other editors

Format with Maven before pushing:

```bash
mvn spotless:apply
```

Or set up the pre-commit hook above so it happens automatically.