# Translating flintTerm

Every piece of text the app shows lives in one file:

    app/src/main/res/values/strings.xml

Nothing is written in the code any more, so adding a language means adding one
file and changing nothing else.

## Adding a language

1. Copy `app/src/main/res/values/strings.xml` to
   `app/src/main/res/values-<code>/strings.xml`, where `<code>` is the language:
   `values-de` for German, `values-pl` for Polish, `values-pt-rBR` for Brazilian
   Portuguese (note the `r` before a region).
2. Translate the text between the tags. Leave the `name="..."` attributes alone,
   they are what the code looks up.
3. Open a pull request. Nothing else has to change: Android picks the file up on
   its own, and the per-app language list is built from whichever folders exist.

You do not have to translate everything. Any string you leave out falls back to
English, so a partial translation is welcome and can be finished later.

## The three rules

**Keep the placeholders.** `%1$s` is where a value is dropped in, and the number
says which one:

    <string name="hostsscreen_sent_received">Sent %1$s · received %2$s</string>

Move them wherever the sentence needs them, but do not renumber them and do not
remove any. A string with a placeholder missing crashes the screen it is on.

**Escape apostrophes.** Write `\'` and not `'`. The same goes for a double quote,
`\"`. This is an Android rule, not ours.

**Leave `<xliff:g>` and anything in backticks alone.** Command names, `TERM`
values and file paths are not words to translate.

## Trying it before you send it

    ./gradlew installDebug

then set the phone's language, or pick the app's own language under
Settings → Apps → flintTerm → Language on Android 13 and later.

A debug build also carries two fake languages for testing layout without knowing
a real one. **Accented English** (`en-XA`) makes every string about a third
longer, which is what most translations do, and shows which rows run out of
room. **Right-to-left English** (`en-XB`) mirrors the layout the way Arabic or
Hebrew would. Both are in the phone's language list once a debug build is
installed.

## For maintainers

`values/strings.xml` is the source. When you add a string, add it there; lint
reports `MissingTranslation` once a second language exists, so the gaps are
visible at build time.
