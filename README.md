# kotbrowse

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

**EN:** a small browser engine in Kotlin (JDK only): HTTP fetching, a forgiving HTML parser with implicit end tags, a CSS parser with selectors/specificity/shorthands, the cascade with inheritance, block and inline layout with margin collapsing and line breaking, and Java2D painting to PNG. 147 tests including an end-to-end load through a local HTTP server. MIT.
