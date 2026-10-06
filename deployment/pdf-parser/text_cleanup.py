"""Keep Docling's layout order, restore trustworthy native PDF word spacing."""
import difflib
import re


def restore_spacing(parsed, native):
    native = native.replace("\r\n", "\n").replace("\ufffe", "-").strip()
    # A near-identical glyph sequence permits using the original text, rather
    # than inventing spaces in identifiers or correcting OCR by guesswork.
    def glyphs(value):
        return "".join(c.casefold() for c in value if c.isalnum())
    expected, actual = glyphs(parsed), glyphs(native)
    if len(expected) < 25 or not actual or not 0.8 <= len(actual) / len(expected) <= 1.2:
        return parsed
    numbers = lambda text: re.findall(r"[0-9]+(?:[.,][0-9]+)*", text)
    if numbers(parsed) != numbers(native):
        return parsed
    if difflib.SequenceMatcher(None, expected, actual, autojunk=False).ratio() < 0.96:
        return parsed
    if native.count(" ") >= parsed.count(" ") + 2:
        # Keep wrapping for the Java prose normalizer to distinguish hard and
        # soft hyphens before assigning source offsets.
        return native
    return parsed


class NativeText:
    def __init__(self, data, enabled=True):
        import pypdfium2
        self.document = pypdfium2.PdfDocument(data) if enabled else None
        self.pages = {}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        for page, text in self.pages.values():
            text.close()
            page.close()
        if self.document is not None:
            self.document.close()

    def restore(self, item, height):
        parsed = item.text.strip()
        if self.document is None or not item.prov:
            return parsed
        return restore_spacing(parsed, self.bounded(item,height))

    def bounded(self, item, height):
        if self.document is None or not item.prov:
            return ""
        prov = item.prov[0]
        index = prov.page_no - 1
        if index not in self.pages:
            page = self.document[index]
            self.pages[index] = (page, page.get_textpage())
        bbox = prov.bbox.to_bottom_left_origin(height)
        return self.pages[index][1].get_text_bounded(left=bbox.l-0.3, bottom=bbox.b-0.3,
                                                    right=bbox.r+0.3, top=bbox.t+0.3)


def table_warning(markdown):
    rows = [line.strip() for line in markdown.splitlines() if line.strip().startswith("|")]
    if len(rows) < 3:
        return False
    cells = lambda line: [value.strip() for value in line.split("|")[1:-1]]
    header = cells(rows[0])
    return any(not cell for cell in header) or any(len(cells(line)) != len(header) for line in rows[2:])


def restore_numeric_table(markdown, native):
    """Repair only unambiguous native numeric rows; never infer or calculate a cell."""
    if not native or not table_warning(markdown):
        return markdown
    lines = markdown.splitlines()
    positions = [i for i,line in enumerate(lines) if line.strip().startswith("|")]
    if len(positions) < 5:
        return markdown
    cells = lambda line: [value.strip() for value in line.split("|")[1:-1]]
    header = cells(lines[positions[0]])
    width = len(header)
    if width < 3 or not header[0] or not header[1]:
        return markdown
    native_lines = [line.strip() for line in native.splitlines() if line.strip()]
    actual_header = next((line.split() for line in native_lines if len(line.split())==width
                          and line.split()[:2]==header[:2]),None)
    if actual_header is None:
        return markdown
    key = lambda value: "".join(c.casefold() for c in value if c.isalnum())
    number = re.compile(r"(?:[+-]?\d+(?:[.,]\d+)*(?:%)?|[-—])$")
    recovered = {}
    for line in native_lines:
        tokens=line.split()
        if len(tokens)<width:
            continue
        values=tokens[-(width-1):]
        label=" ".join(tokens[:-(width-1)])
        if label and all(number.fullmatch(value) for value in values):
            recovered[key(label)]=[label]+values
    matched = [i for i in positions[2:] if key(cells(lines[i])[0]) in recovered]
    if len(matched)<3:
        return markdown
    # The first numeric column must agree wherever Docling read it reliably.
    for i in matched:
        row=cells(lines[i]); original=recovered[key(row[0])]
        if len(row)>1 and row[1] and number.fullmatch(row[1]) and row[1]!=original[1]:
            return markdown
    lines[positions[0]]="| "+" | ".join(actual_header)+" |"
    lines[positions[1]]="| "+" | ".join(["---"]*width)+" |"
    for i in matched:
        lines[i]="| "+" | ".join(recovered[key(cells(lines[i])[0])])+" |"
    return "\n".join(lines)
