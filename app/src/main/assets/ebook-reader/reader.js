import './view.js'
import * as CFI from './epubcfi.js'

// For the bridge (android-interface.js), which isn't a module and can't import it.
window.CFI = CFI
import { Overlayer } from './overlayer.js'


window.createReader = () => new Reader()

class Reader {
    style = {
        lineHeight: 1.4,
        justify: true,
        hyphenate: true,
        isDark: false,
        theme: {
           name: 'default',
           light: { fg: '#000000', bg: '#ffffff', link: '#0066cc' },
           dark: { fg: '#e0e0e0', bg: '#222222', link: '#88ccee' },
       },
    }

    layout = {
        gap: 0.06,
        maxInlineSize: 1440,
        maxBlockSize: 720,
        maxColumnCount: 2,
        flow: 'paginated',
    }

    /** CSS appended to every chapter's styles; see setExtraStyles in android-interface.js. */
    extraCss = ''

    annotations = new Map()
    annotationsByValue = new Map()
    
    async open(view, file) {
        this.view = view
        document.body.append(view)
        // foliate-js works out the format itself (see makeBook in view.js).
        await view.open(file)
        const book = view.book

        this.setAppearance(this.style, this.layout)
        view.next()

        // load and show highlights embedded in the file by Calibre
        const bookmarks = await book.getCalibreBookmarks?.()
        if (bookmarks) {
            const { fromCalibreHighlight } = await import('./epubcfi.js')
            for (const obj of bookmarks) {
                if (obj.type === 'highlight') {
                    const value = fromCalibreHighlight(obj)
                    const color = obj.style.which
                    const note = obj.notes
                    const annotation = { value, color, note }
                    const list = this.annotations.get(obj.spine_index)
                    if (list) list.push(annotation)
                    else this.annotations.set(obj.spine_index, [annotation])
                    this.annotationsByValue.set(value, annotation)
                }
            }

            view.addEventListener('create-overlay', e => {
                const { index } = e.detail
                const list = this.annotations.get(index)
                if (list) for (const annotation of list)
                    view.addAnnotation(annotation)
            })

            view.addEventListener('draw-annotation', e => {
                const { draw, annotation } = e.detail
                const { color } = annotation
                draw(Overlayer.highlight, { color })
            })

            view.addEventListener('show-annotation', e => {
                const annotation = this.annotationsByValue.get(e.detail.value)
                if (annotation.note) alert(annotation.note)
            })
        }
    }

    setAppearance(style, layout) {
        Object.assign(this.style, style)
        const { theme } = style
        const $style = document.documentElement.style

        if (style.isDark) {
            $style.setProperty('--bg', theme.dark.bg)
            $style.setProperty('--fg', theme.dark.fg)
        }
        else {
            $style.setProperty('--bg', theme.light.bg)
            $style.setProperty('--fg', theme.light.fg)
        }

        const renderer = this.view?.renderer
        if (renderer) {
            renderer.setAttribute('flow', layout.flow)
            renderer.setAttribute('gap', layout.gap * 100 + '%')
            renderer.setAttribute('max-inline-size', layout.maxInlineSize + 'px')
            renderer.setAttribute('max-block-size', layout.maxBlockSize + 'px')
            renderer.setAttribute('max-column-count', layout.maxColumnCount)
            renderer.setStyles?.(getCSS(this.style, this.extraCss))
        }

        document.body.classList.toggle('dark', this.style.isDark)
    }

    /** Re-sends the chapter styles alone, as after a change to extraCss. */
    applyStyles() {
        this.view?.renderer?.setStyles?.(getCSS(this.style, this.extraCss))
    }
}


const getCSS = ({ isDark, lineHeight, justify, hyphenate, theme }, extraCss = '') => [`
    @namespace epub "http://www.idpf.org/2007/ops";
    html {
        color-scheme: 'only light';
        color: ${isDark ? theme.dark.fg : theme.light.fg};
    }
    a:any-link {
        color: ${isDark ? theme.dark.link : theme.light.link};
    }

    aside[epub|type~="endnote"],
    aside[epub|type~="footnote"],
    aside[epub|type~="note"],
    aside[epub|type~="rearnote"] {
        display: none;
    }

    html, body, p, li, blockquote, dd {
        line-height: ${lineHeight};
        text-align: ${justify ? 'justify' : 'start'};
        -webkit-hyphens: ${hyphenate ? 'auto' : 'manual'};
        -webkit-hyphenate-limit-before: 3;
        -webkit-hyphenate-limit-after: 2;
        -webkit-hyphenate-limit-lines: 2;
        hanging-punctuation: allow-end last;
        orphans: 2;
        widows: 2;
    }
    /* prevent the above from overriding the align attribute */
    [align="left"] { text-align: left; }
    [align="right"] { text-align: right; }
    [align="center"] { text-align: center; }
    [align="justify"] { text-align: justify; }

    pre {
        white-space: pre-wrap !important;
        tab-size: 2;
    }
`, `
    p, li, blockquote, dd {
        line-height: ${lineHeight};
        text-align: ${justify ? 'justify' : 'start'};
        -webkit-hyphens: ${hyphenate ? 'auto' : 'manual'};
    }
    ${extraCss}
`]
