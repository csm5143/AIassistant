import unittest
from text_cleanup import restore_spacing, table_warning, restore_numeric_table


class NativeSpacingTest(unittest.TestCase):
    def test_restore_only_same_glyphs_and_never_invent_numbers(self):
        parsed = "ThedecoderisalsocomposedofastackofN=6identicallayers."
        native = "The decoder is also composed of a stack of N = 6 identical layers."
        self.assertEqual(native, restore_spacing(parsed, native))
        self.assertEqual(parsed, restore_spacing(parsed, native.replace("6", "800")))

    def test_keep_identifiers_and_header_risks_visible(self):
        original = "See https://example.org/v4.1 and model_v4.1 for the measured accuracy of 69.2."
        self.assertEqual(original, restore_spacing(original, original))
        self.assertFalse(table_warning("| Model | Avg |\n|---|---|\n| Dense |69.2|"))
        self.assertTrue(table_warning("| Model | |\n|---|---|\n| Dense |69.2|"))
        self.assertTrue(table_warning("| Model | Avg |\n|---|---|\n| Dense |69.2|56.9|"))

    def test_numeric_table_repair_requires_native_header_rows_and_agreement(self):
        broken="Table 1\n| Model | Avg |  |\n|---|---|---|\n| Dense |69.2|56.9|\n| Sparse |53.9|43.8|\n| All |71.5|59.6|"
        native="Model Avg en\nDense 69.2 56.9\nSparse 53.9 43.8\nAll 71.5 59.6"
        repaired=restore_numeric_table(broken,native)
        self.assertIn("| Model | Avg | en |",repaired)
        self.assertIn("| Dense | 69.2 | 56.9 |",repaired)
        self.assertFalse(table_warning(repaired))
        self.assertEqual(broken,restore_numeric_table(broken,native.replace("Dense 69.2","Dense 70.4")))
        self.assertEqual(broken,restore_numeric_table(broken,"No reliable native table"))


if __name__ == "__main__":
    unittest.main()
