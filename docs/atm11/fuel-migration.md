# Chemical fuel migration

ATM11 0.14.0-beta supplies `burn_time` and `energy` in its dynamic chemical fuel data map. The older Mekanism model stores ticks per mB and energy per tick: total energy per mB is their product. See the [upstream 1.21 implementation](https://github.com/mekanism/Mekanism/blob/1.21.x/src/api/java/mekanism/api/datamaps/chemical/attribute/ChemicalFuel.java).

The current port uses `maxBurnPerTick` in mB per tick and stores energy per mB directly. These rate fields are not interchangeable. The pack repair preserves the old energy density: ethene becomes 6,000 energy per mB and hydrogen becomes 200. It reads the burn limit from the installed port's own default data map, currently 10 mB per tick for both fuels, rather than treating old burn duration as a consumption rate.

This preserves fuel energy content with the port's current generator design. It does not reproduce the older fractional burn timing or certify identical peak throughput. Runtime QA checks the actual loaded data-map entries so silent fallback to the port's default energy densities is detected.
