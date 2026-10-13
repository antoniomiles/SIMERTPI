enum WelcomeMode { day, night }

/// Ecuador continental uses UTC-5 year-round; no device timezone is consulted.
DateTime ecuadorContinentalTime(DateTime instant) =>
    instant.toUtc().subtract(const Duration(hours: 5));

WelcomeMode welcomeModeAt(DateTime instant) {
  final localHour = ecuadorContinentalTime(instant).hour;
  return localHour >= 6 && localHour < 19 ? WelcomeMode.day : WelcomeMode.night;
}

DateTime nextWelcomeModeChangeUtc(DateTime instant) {
  final local = ecuadorContinentalTime(instant);
  final nextHour = local.hour < 6 || local.hour >= 19 ? 6 : 19;
  var nextLocalBoundary = DateTime.utc(
    local.year,
    local.month,
    local.day,
    nextHour,
  );
  if (!nextLocalBoundary.isAfter(local)) {
    nextLocalBoundary = nextLocalBoundary.add(const Duration(days: 1));
  }
  return nextLocalBoundary.add(const Duration(hours: 5));
}
