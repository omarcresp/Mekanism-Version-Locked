import tomllib
import unittest
from tools.atm11.balance import rewrite_config


class BalanceMigrationTest(unittest.TestCase):
    def test_preserves_unrelated_values_and_table_ownership(self):
        original = '# keep this\nusage = 5 # watts\nother = 7\n[teleporter]\nbase = 100\nother = 9\n'
        values = {'usage': 20, 'capacity': 8000, 'teleporter.base': 400, 'teleporter.distance': 4}
        updated, count = rewrite_config(original, values)
        self.assertEqual(count, 4)
        self.assertEqual(tomllib.loads(updated), {'usage': 20, 'capacity': 8000, 'other': 7,
                                                'teleporter': {'base': 400, 'distance': 4, 'other': 9}})
        self.assertIn('# keep this', updated)
        self.assertIn('# watts', updated)
        self.assertEqual(rewrite_config(updated, values), (updated, 0))

    def test_creates_missing_root_and_sections(self):
        updated, count = rewrite_config('', {'usage': 20, 'teleporter.base': 400, 'teleporter.distance': 4})
        self.assertEqual(count, 3)
        self.assertEqual(tomllib.loads(updated), {'usage': 20, 'teleporter': {'base': 400, 'distance': 4}})
        self.assertEqual(rewrite_config(updated, {'usage': 20, 'teleporter.base': 400}), (updated, 0))

    def test_corrects_wrong_scalar_type(self):
        updated, count = rewrite_config('randomizedConsumption = 0\n', {'randomizedConsumption': False})
        self.assertEqual(count, 1)
        self.assertIs(tomllib.loads(updated)['randomizedConsumption'], False)
