var view, reader

document.addEventListener('DOMContentLoaded', async () => {
    view = document.createElement('foliate-view')
    reader = window.createReader()
    bookLoaded = false

    const onLoad = async ({ detail: { doc } }) => {
        if (bookLoaded) return

        const { book } = view
        const metadata = book.metadata

        // foliate-js gives titles and names as a language map ({ en: …, ja: … }) when a book has
        // them in several scripts, and people (authors, publishers…) as objects; the app reads
        // plain text, so everything is flattened here.
        const text = x => typeof x === 'string' ? x
            : x && typeof x === 'object' ? text(x.name ?? Object.values(x)[0]) : x == null ? null : String(x)
        const list = x => Array.isArray(x) ? x : x == null ? [] : [x]
        const names = x => list(x).map(p => ({ name: text(p) ?? '' }))
        const data = {
            title: text(metadata?.title),
            subtitle: text(metadata?.subtitle),
            author: names(metadata?.author),
            description: text(metadata?.description),
            identifier: text(metadata?.identifier),
            language: list(metadata?.language).map(text),
            publisher: list(metadata?.publisher).map(text).join(', ') || null,
            contributor: names(metadata?.contributor),
            published: text(metadata?.published),
            modified: text(metadata?.modified),
            subject: names(metadata?.subject),
            rights: text(metadata?.rights),
            toc: book.toc ?? [],
        }

        bookLoaded = true
        AndroidInterface.onBookLoaded(JSON.stringify(data))
    }

    lastLocation = { cfi:null, fraction: null }
    const onRelocate = ({ detail }) => {
        if (detail.cfi == lastLocation.cfi || detail.fraction == lastLocation.fraction) return
        lastLocation = Object.assign(detail)

        const data = {
            cfi: detail.cfi,
            fraction: detail.fraction,
            excerpt: pageExcerpt(detail.range),
            bookmarked: pageHasBookmark(detail.cfi),
            tocItem: detail.tocItem,
            currentLocation: detail.location?.current ?? 0,
            nextLocation: detail.location?.next ?? 0,
            totalLocations: detail.location?.total ?? 0,
        }

        if(bookLoaded) AndroidInterface.onRelocated(JSON.stringify(data))
    }

    view.addEventListener('load', onLoad)
    view.addEventListener('relocate', onRelocate)

    const params = new URLSearchParams(location.search)
    const url = params.get('url')

    try {
        const response = await fetch(url)
        const blob = await response.blob()
        const fileName = new URL(url).pathname
        reader.open(view, new File([blob], fileName))
    } catch (error) {
        console.error(error)
        AndroidInterface.onBookLoadFailed(JSON.stringify(error, Object.getOwnPropertyNames(error)))
    }
})


const next = () => view.next()

const prev = () => view.prev()

const goto = (locator) => view.goTo(locator)

const gotoFraction = (fraction) => view.goToFraction(fraction)

const getTocFractions = () => {
    const tocFractions = []
    const sizes = view.book.sections.filter(s => s.linear !== 'no').map(s => s.size)
    if (sizes.length < 100) {
        const total = sizes.reduce((a, b) => a + b, 0)
        let sum = 0
        for (const size of sizes.slice(0, -1)) {
            sum += size
            const fraction = sum / total
            tocFractions.push(fraction)
        }
    }
    return tocFractions
}

const getAppearance = () => {
    const style = reader.style
    const layout = reader.layout

    const appearance = {};
    appearance.lineHeight = style.lineHeight
    appearance.justify = style.justify
    appearance.hyphenate = style.hyphenate
    appearance.themeName = style.theme.name
    appearance.lightFg = style.theme.light.fg
    appearance.lightBg = style.theme.light.bg
    appearance.lightLink = style.theme.light.link
    appearance.darkFg = style.theme.dark.fg
    appearance.darkBg = style.theme.dark.bg
    appearance.darkLink = style.theme.dark.link
    appearance.useDark = style.isDark

    appearance.gap = layout.gap
    appearance.maxInlineSize = layout.maxInlineSize
    appearance.maxBlockSize = layout.maxBlockSize
    appearance.maxColumnCount = layout.maxColumnCount
    appearance.flow = layout.flow

    return appearance
}

const setAppearance = (appearance) => {
    const style = { theme: { light: {}, dark: {} } }
    style.lineHeight = appearance.lineHeight
    style.justify = appearance.justify
    // Spelt "hypenate" in the original bridge, which the engine never read: the setting was lost
    // and hyphenation stayed on whatever it was set to.
    style.hyphenate = appearance.hyphenate
    style.theme.name = appearance.themeName
    style.theme.light.fg = appearance.lightFg
    style.theme.light.bg = appearance.lightBg
    style.theme.light.link = appearance.lightLink
    style.theme.dark.fg = appearance.darkFg
    style.theme.dark.bg = appearance.darkBg
    style.theme.dark.link = appearance.darkLink
    style.isDark = appearance.useDark

    const layout = {}
    layout.gap = appearance.gap
    layout.maxInlineSize = appearance.maxInlineSize
    layout.maxBlockSize = appearance.maxBlockSize
    layout.maxColumnCount = appearance.maxColumnCount
    layout.flow = appearance.flow

    reader.setAppearance(style, layout)
}


// Dantotsu additions to the bridge.

/** The start of the text on the page, for naming a bookmark. */
const pageExcerpt = (range) => {
    try {
        return (range?.toString() ?? '').replace(/\s+/g, ' ').trim().slice(0, 160)
    } catch (e) { return '' }
}

/** Positions (CFIs) bookmarked in this book, which the app keeps; see setBookmarks. */
let bookmarkCfis = []

/** Whether one of [bookmarkCfis] falls on the page [pageCfi] spans. */
const pageHasBookmark = (pageCfi) => {
    if (!pageCfi || !bookmarkCfis.length) return false
    try {
        const start = CFI.collapse(pageCfi)
        const end = CFI.collapse(pageCfi, true)
        return bookmarkCfis.some(c => {
            const at = CFI.collapse(c)
            return CFI.compare(at, start) >= 0 && CFI.compare(at, end) <= 0
        })
    } catch (e) { return false }
}

/** The bookmarked positions on the page on screen, for removing them. */
const bookmarksOnPage = () => {
    const pageCfi = view?.lastLocation?.cfi
    if (!pageCfi) return []
    try {
        const start = CFI.collapse(pageCfi)
        const end = CFI.collapse(pageCfi, true)
        return bookmarkCfis.filter(c => {
            const at = CFI.collapse(c)
            return CFI.compare(at, start) >= 0 && CFI.compare(at, end) <= 0
        })
    } catch (e) { return [] }
}

/** Takes the book's bookmarks and says again whether the page on screen has one. */
const setBookmarks = (cfis) => {
    bookmarkCfis = cfis || []
    const cfi = view?.lastLocation?.cfi
    if (cfi) AndroidInterface.onBookmarkState(pageHasBookmark(cfi))
}

/**
 * Searches the book, sending each chapter's matches as they are found (foliate-js outlines them
 * on the page too), then onSearchDone. A newer search, or clearSearch, ends an older one.
 */
let searchRun = 0
const searchBook = async (query) => {
    const run = ++searchRun
    try {
        for await (const result of view.search({ query })) {
            if (run !== searchRun) return
            if (result === 'done') break
            if (result.subitems) {
                AndroidInterface.onSearchResults(JSON.stringify({
                    label: result.label ?? '',
                    items: result.subitems.map(({ cfi, excerpt }) => ({
                        cfi,
                        pre: excerpt?.pre ?? '',
                        match: excerpt?.match ?? '',
                        post: excerpt?.post ?? '',
                    })),
                }))
            }
        }
    } catch (e) {
        console.error(e)
    }
    if (run === searchRun) AndroidInterface.onSearchDone()
}

const clearSearch = () => {
    searchRun++
    view?.clearSearch()
}

/**
 * Extra CSS for every chapter, after the reader's own: the app's font choice. Kept on the reader,
 * which hands it to each chapter as it lays it out (see Reader.applyStyles).
 */
const setExtraStyles = (css) => {
    reader.extraCss = css || ''
    reader.applyStyles()
    // A font file the web view's font checker refuses (which Android itself might still draw)
    // fails here and nowhere else; say so rather than quietly showing the fallback.
    if (css && css.includes('@font-face')) setTimeout(async () => {
        try {
            const doc = view.renderer.getContents()[0]?.doc
            await doc?.fonts?.ready
            if ([...doc.fonts].some(f => f.family.includes('DantotsuNovelFont') && f.status === 'error'))
                AndroidInterface.onFontFailed()
        } catch (e) {}
    }, 1500)
}

/**
 * Marks the read-aloud sentence: [selector] matched in every rendered section gets [className]
 * and a background of [colour]; whatever had it before loses it. A null selector only clears.
 *
 * Inline styles rather than a stylesheet rule, so it doesn't depend on the book's own CSS having
 * loaded or losing to it; the class is only the handle for finding what to clear next time.
 */
const highlightSentence = (selector, className, colour) => {
    let contents = []
    try { contents = view.renderer.getContents() || [] } catch (e) { return }
    for (const { doc } of contents) {
        if (!doc) continue
        try {
            const lit = doc.getElementsByClassName(className)
            while (lit.length) {
                lit[0].style.backgroundColor = ''
                lit[0].style.borderRadius = ''
                lit[0].classList.remove(className)
            }
            if (!selector) continue
            for (const el of doc.querySelectorAll(selector)) {
                el.classList.add(className)
                el.style.backgroundColor = colour
                el.style.borderRadius = '2px'
            }
        } catch (e) {}
    }
}

function getBlobAsBase64(blobUrl, callback) {
  fetch(blobUrl)
    .then(response => response.blob())
    .then(blobData => {
      const reader = new FileReader()
      reader.onload = () => {
        const base64String = reader.result
        callback(base64String)
      }
      reader.readAsDataURL(blobData)
    })
    .catch(error => {
      console.error('Error fetching the blob:', error)
    })
}