import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from patch_pack import migrate_fuel


class FuelMigrationTest(unittest.TestCase):
    def migrate(self, values):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        path = root / 'kubegroovy/dynamic_data/mekanism/data_maps/mekanism/chemical/chemical_attribute_fuel.json'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({'values': values}))
        count = migrate_fuel(root)
        result = json.loads(path.read_text())['values']
        self.assertEqual(migrate_fuel(root), 0)
        return count, result

    def test_legacy_atm_fuels_convert_joules_and_retain_burn_timing(self):
        count, values = self.migrate({'mekanism:ethene': {'burn_time': 20, 'energy': 300},
                                     'mekanism:hydrogen': {'burn_time': 1, 'energy': 200}})
        self.assertEqual(count, 2)
        self.assertEqual(values['mekanism:ethene'], {'energy': 2400, 'maxBurnPerTick': 256, 'burnTicksPerMB': 20})
        self.assertEqual(values['mekanism:hydrogen'], {'energy': 80, 'maxBurnPerTick': 256})

    def test_rc1_converted_overlay_is_corrected(self):
        count, values = self.migrate({'mekanism:ethene': {'energy': 6000, 'maxBurnPerTick': 10},
                                     'mekanism:hydrogen': {'energy': 200, 'maxBurnPerTick': 10}})
        self.assertEqual(count, 2)
        self.assertEqual(values['mekanism:ethene']['energy'], 2400)
        self.assertEqual(values['mekanism:ethene']['burnTicksPerMB'], 20)
        self.assertEqual(values['mekanism:hydrogen']['energy'], 80)

    def test_custom_legacy_fuel_converts_without_rewriting_modern_fuels(self):
        count, values = self.migrate({'test:legacy': {'burn_time': 5, 'energy': 15},
                                     'test:modern': {'energy': 17, 'maxBurnPerTick': 3}})
        self.assertEqual(count, 1)
        self.assertEqual(values['test:legacy'], {'energy': 30, 'maxBurnPerTick': 256, 'burnTicksPerMB': 5})
        self.assertEqual(values['test:modern'], {'energy': 17, 'maxBurnPerTick': 3})
