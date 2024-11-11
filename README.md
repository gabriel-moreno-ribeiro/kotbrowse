# kotbrowse

A small web browser engine written from scratch in Kotlin. It fetches a
page over HTTP, parses the HTML into a DOM, parses the CSS (author
stylesheets, `<style>` blocks, inline styles and a built-in user agent
sheet), runs the cascade with specificity and inheritance, lays the page
out with block and inline formatting, and paints the result to a PNG.
No libraries beyond the JDK.

```sh
sh build.sh                                   # needs kotlinc and a JDK 17+
java -jar build/kotbrowse.jar https://example.com -o page.png
java -jar build/kotbrowse.jar examples/index.html --dump --text
```

Options: `-o file.png` output, `-w 800` viewport width, `--dump` prints the
layout tree, `--text` prints the page as text.

## Pipeline

1. **Fetch** (`Net.kt`): `http(s):` and `file:` URLs, redirects, charset
   detection from the header or `<meta charset>`. Stylesheets linked with
   `<link rel=stylesheet>` and images are fetched relative to the page URL.
2. **HTML** (`Html.kt`): a forgiving tokenizer and tree builder. Handles
   attributes with or without quotes, comments, doctype, entities
   (`&amp;`, `&#65;`, `&#x41;`, common named ones), void elements, raw
   `<script>`/`<style>` content, and implicit end tags: a block closes an
   open `<p>`, `<li>` closes the previous `<li>`, and so on. Missing
   `html`/`body` elements are created like a real browser does.
3. **CSS** (`Css.kt`): selectors with type, `#id`, `.class`, `[attr]`,
   `[attr=value]`, `*`, descendant and child combinators (selectors with
   unsupported parts such as `:hover` never match), specificity,
   `!important`, comments, `@media` blocks and shorthand expansion for
   `margin`, `padding`, `border`, `border-*`, `background`, `font` and
   `list-style`. Colours: named, `#rgb`, `#rrggbb(aa)`, `rgb()`, `rgba()`.
   Lengths: `px`, `em`, `rem`, `%`, `pt` and friends.
4. **Style** (`Style.kt`): the cascade sorts matching declarations by
   importance, origin (user agent, author, inline), specificity and order,
   then computes values: inherited properties (colour, font, text-align,
   white-space, text-decoration, list style) flow from the parent, relative
   font sizes resolve against the parent, borders are zero unless a style is
   set. Presentational attributes such as `width=` and `bgcolor=` and the
   `hidden` attribute are honoured too.
5. **Layout** (`Layout.kt`): the styled tree becomes a box tree of block,
   inline, anonymous, text and image boxes. Block layout computes widths
   (auto, fixed, percentages, `max-width`, centring with `margin: auto`),
   stacks children vertically and collapses adjacent vertical margins.
   Inline layout collapses whitespace, breaks lines greedily, honours
   `<br>`, `white-space: pre`, `text-align`, images as atomic inlines, and
   sizes lines from the tallest font on them. Text measurement is an
   interface so the layout can be tested with fixed metrics.
6. **Paint** (`Paint.kt`): the box tree is flattened into a display list
   (backgrounds, borders, list markers, text, images) which is rasterised
   with Java2D into a PNG.

## Tests

`sh test.sh` runs the suite: HTML parsing edge cases, selector matching
and specificity, shorthand expansion, colour and length parsing,
inheritance and cascade order, dozens of layout geometry checks with
deterministic font metrics, pixel checks on rendered output, and an
end-to-end test that starts a local HTTP server and loads a page through
a redirect with a linked stylesheet, an image and a missing image.

The `examples/` page exercises most of the engine; CI renders it and
uploads the PNG as an artifact.

## License

MIT
