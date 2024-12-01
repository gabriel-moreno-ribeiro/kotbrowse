# kotbrowse

> 🇺🇸 [English version below](#english)

Um motor de navegador pequeno em Kotlin. Ele busca a página por HTTP, parseia o HTML numa DOM, parseia o CSS (folhas do autor, `<style>`, `style=""` e uma folha de user agent embutida), roda a cascata com especificidade e herança, faz o layout com formatação de bloco e inline e pinta o resultado num PNG. Só o JDK.

Não renderiza o Facebook. Renderiza páginas normais de texto, com títulos, listas, `pre`, links, imagens e a folha de estilo linkada, e isso já é o suficiente pra entender de verdade o que o Chrome faz quando você aperta Enter.

```sh
sh build.sh                                   # kotlinc + JDK 17+
java -jar build/kotbrowse.jar https://example.com -o page.png
java -jar build/kotbrowse.jar examples/index.html --dump --text
```

`-o` arquivo de saída, `-w 800` largura, `--dump` imprime a árvore de layout, `--text` imprime a página como texto.

## O pipeline

1. **Fetch** (`Net.kt`): `http(s):` e `file:`, redirects, charset do header ou do `<meta charset>`. Folhas linkadas e imagens são buscadas relativas à URL da página.
2. **HTML** (`Html.kt`): tokenizer e construtor de árvore tolerantes. Atributos com ou sem aspas, comentários, doctype, entidades, elementos void, texto cru de `<script>`/`<style>` e os fechamentos implícitos: bloco fecha `<p>` aberto, `<li>` fecha o `<li>` anterior, etc. `html`/`body` faltando são criados, como um navegador de verdade.
3. **CSS** (`Css.kt`): seletores de tipo, `#id`, `.classe`, `[attr]`, `[attr=valor]`, `*`, descendente e filho (seletor com coisa que eu não implementei, tipo `:hover`, nunca casa), especificidade, `!important`, comentários, `@media` simples e expansão de shorthand de `margin`, `padding`, `border`, `background`, `font` e `list-style`. Cores nomeadas, hex, `rgb()`, `rgba()`; comprimentos em `px`, `em`, `rem`, `%`, `pt`.
4. **Estilo** (`Style.kt`): a cascata ordena as declarações por importância, origem (UA, autor, inline), especificidade e ordem, depois computa: propriedades herdadas descem do pai, tamanho de fonte relativo resolve contra o pai, borda é zero sem `border-style`.
5. **Layout** (`Layout.kt`): a árvore estilizada vira caixas de bloco, inline, anônimas, texto e imagem. Bloco calcula largura (auto, fixa, `%`, `max-width`, centralização com `margin: auto`), empilha filhos e colapsa margens verticais. Inline colapsa espaço, quebra linha, respeita `<br>`, `white-space: pre`, `text-align`, imagens como inline atômico. A medição de texto é uma interface, então o layout roda nos testes com métrica fixa.
6. **Pintura** (`Paint.kt`): display list (fundos, bordas, marcadores de lista, texto, imagens) rasterizada com Java2D.

A coisa que mais deu trabalho, de longe, foi o fechamento implícito de tags. HTML "válido" quase não existe na internet e o parser tem que ser gentil com tudo.

Testes: `sh test.sh` (parser de HTML com casos de borda, matching e especificidade de seletor, shorthands, cores e comprimentos, herança e ordem da cascata, dezenas de checagens de geometria de layout com métrica determinística, pixels da imagem renderizada, e um teste de ponta a ponta que sobe um servidor HTTP local e carrega uma página passando por redirect, com folha linkada, uma imagem e uma imagem quebrada). O CI renderiza a página de `examples/` e sobe o PNG como artefato.

---

## English

A small browser engine in Kotlin. It fetches the page over HTTP, parses the HTML into a DOM, parses the CSS (author sheets, `<style>`, `style=""` and a built-in user agent sheet), runs the cascade with specificity and inheritance, does the layout with block and inline formatting and paints the result to a PNG. JDK only.

It doesn't render Facebook. It renders normal text pages, with headings, lists, `pre`, links, images and the linked stylesheet, and that's already enough to really understand what Chrome does when you hit Enter.

```sh
sh build.sh                                   # kotlinc + JDK 17+
java -jar build/kotbrowse.jar https://example.com -o page.png
java -jar build/kotbrowse.jar examples/index.html --dump --text
```

`-o` output file, `-w 800` width, `--dump` prints the layout tree, `--text` prints the page as text.

## The pipeline

1. **Fetch** (`Net.kt`): `http(s):` and `file:`, redirects, charset from the header or from `<meta charset>`. Linked sheets and images are fetched relative to the page URL.
2. **HTML** (`Html.kt`): forgiving tokenizer and tree builder. Attributes with or without quotes, comments, doctype, entities, void elements, raw text of `<script>`/`<style>` and the implicit closings: a block closes an open `<p>`, `<li>` closes the previous `<li>`, etc. Missing `html`/`body` are created, like a real browser.
3. **CSS** (`Css.kt`): type selectors, `#id`, `.class`, `[attr]`, `[attr=value]`, `*`, descendant and child (a selector with something I didn't implement, like `:hover`, never matches), specificity, `!important`, comments, simple `@media` and shorthand expansion of `margin`, `padding`, `border`, `background`, `font` and `list-style`. Named colors, hex, `rgb()`, `rgba()`; lengths in `px`, `em`, `rem`, `%`, `pt`.
4. **Style** (`Style.kt`): the cascade sorts the declarations by importance, origin (UA, author, inline), specificity and order, then computes: inherited properties come down from the parent, relative font size resolves against the parent, border is zero without `border-style`.
5. **Layout** (`Layout.kt`): the styled tree becomes block, inline, anonymous, text and image boxes. Block computes width (auto, fixed, `%`, `max-width`, centering with `margin: auto`), stacks children and collapses vertical margins. Inline collapses whitespace, breaks lines, respects `<br>`, `white-space: pre`, `text-align`, images as atomic inlines. Text measurement is an interface, so the layout runs in the tests with fixed metrics.
6. **Paint** (`Paint.kt`): a display list (backgrounds, borders, list markers, text, images) rasterized with Java2D.

The thing that gave the most work, by far, was the implicit closing of tags. "Valid" HTML barely exists on the internet and the parser has to be gentle with everything.

Tests: `sh test.sh` (HTML parser with edge cases, selector matching and specificity, shorthands, colors and lengths, inheritance and cascade order, dozens of layout geometry checks with deterministic metrics, pixels of the rendered image, and an end-to-end test that brings up a local HTTP server and loads a page through a redirect, with a linked sheet, an image and a broken image). CI renders the page in `examples/` and uploads the PNG as an artifact.

MIT.
