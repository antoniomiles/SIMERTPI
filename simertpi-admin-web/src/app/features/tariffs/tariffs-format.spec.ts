import { currentConfigurationDate, formatConfigurationAmount, formatConfigurationDate, formatConfigurationDateTime } from './tariffs-format';

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
});
