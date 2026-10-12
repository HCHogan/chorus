    //Returns the flinch scaler from Resillience, Stability, Perks, and Buffs
    //flinch resist = 1.0 - flinch scaler
    //flinch resist can be negative
    pub fn calc_flinch_resist(
        &self,
        _calc_input: Option<CalculationInput>,
        _resillience: i32,
        _pvp: bool,
        _cached_data: Option<&mut HashMap<String, f64>>,
    ) -> f64 {
        /*
        Todo:
        X3 Unflinching
        Perfect Float
         */
        let mut default_cached_data = HashMap::new();
        let cached_data = _cached_data.unwrap_or(&mut default_cached_data);
        let mut total_scaler = 1.0;

        //resil
        let resillience: f64 = _resillience.clamp(0, 10).into();
        total_scaler *= 1.0 - resillience * 0.01;

        //stability
        let stability_percent = match self.weapon_type {
            WeaponType::AUTORIFLE => 0.25,
            WeaponType::SUBMACHINEGUN => 0.25,
            WeaponType::BOW => 0.25,
            WeaponType::PULSERIFLE => 0.2,
            WeaponType::SCOUTRIFLE => 0.2,
            WeaponType::SIDEARM => 0.2,
            WeaponType::MACHINEGUN => 0.2,
            WeaponType::HANDCANNON => 0.15,
            WeaponType::TRACERIFLE => 0.25,
            WeaponType::FUSIONRIFLE => 0.1,
            WeaponType::SHOTGUN => 0.1,
            WeaponType::SNIPER => 0.1,
            WeaponType::GRENADELAUNCHER => 0.1,
            WeaponType::LINEARFUSIONRIFLE => 0.1,
            WeaponType::ROCKET => 0.1,
            _ => 0.0,
        };

        let total_stability: f64 = self
            .stats
            .get(&StatHashes::STABILITY.into())
            .unwrap_or(&Stat::new())
            .perk_val()
            .clamp(0, 100)
            .into();
        total_scaler *= 1.0 - ((total_stability - 20.0) / 80.0 * stability_percent);
        if let Some(calc_input) = _calc_input {
            total_scaler *=
                get_flinch_modifier(self.list_perks(), &calc_input, _pvp, cached_data).flinch_scale;
        }

        total_scaler
    }
}
