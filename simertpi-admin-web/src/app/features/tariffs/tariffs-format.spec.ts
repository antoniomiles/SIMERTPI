import { currentConfigurationDate, formatConfigurationAmount, formatConfigurationDate, formatConfigurationDateTime, isValidConfigurationDate, isoToGuayaquilDateTimeLocal, localDateTimeToIso, nextConfigurationDate } from './tariffs-format';

describe('Spanish Ecuador configuration formatting', () => {
  it('formats date-only contract values without shifting the calendar day', () => {
    expect(formatConfigurationDate('2027-01-01')).toBe('1 de enero de 2027');
  });

  it('formats date-times in Spanish using the Piñas timezone', () => {
    expect(formatConfigurationDateTime('2027-01-01T05:00:00Z')).toContain('1 de enero de 2027');
    expect(formatConfigurationDateTime('2027-01-01T05:00:00Z')).toMatch(/\d{1,2}:\d{2}/);
  });

  it('formats amounts with Ecuadorian decimal punctuation and keeps their currency visible', () => {
    expect(formatConfigurationAmount('0.25', 'USD')).toBe('0,25 USD');
    expect(formatConfigurationAmount('0.25', null)).toBe('0,25 Moneda no configurada');
  });

  it('uses the Guayaquil calendar date for date-only validity decisions', () => {
    expect(currentConfigurationDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('calculates the current and next configuration days in Ecuador rather than the device timezone', () => {
    vi.useFakeTimers();
    try {
      // 03:30 UTC is still 22:30 on the previous calendar day in Guayaquil.
      vi.setSystemTime(new Date('2026-10-22T03:30:00.000Z'));
      expect(currentConfigurationDate()).toBe('2026-10-21');
      expect(nextConfigurationDate()).toBe('2026-10-22');
    } finally {
      vi.useRealTimers();
    }
  });

  it('accepts only real ISO calendar dates', () => {
    expect(isValidConfigurationDate('2027-01-01')).toBe(true);
    expect(isValidConfigurationDate('2027-02-29')).toBe(false);
    expect(isValidConfigurationDate('2027-13-01')).toBe(false);
    expect(isValidConfigurationDate('01/01/2027')).toBe(false);
  });

  it('converts datetime-local values using Guayaquil time regardless of the administrator device timezone', () => {
    expect(localDateTimeToIso('2027-01-01T00:00')).toBe('2027-01-01T05:00:00.000Z');
    expect(isoToGuayaquilDateTimeLocal('2027-01-01T05:00:00Z')).toBe('2027-01-01T00:00');
  });

  it('rejects malformed and impossible calendar dates instead of sending a technical date error', () => {
    expect(localDateTimeToIso('2027-02-29T10:00')).toBeNull();
    expect(localDateTimeToIso('not-a-date')).toBeNull();
  });
});
