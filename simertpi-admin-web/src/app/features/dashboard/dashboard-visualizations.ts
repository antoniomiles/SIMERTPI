import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminReportResponse } from '../reports/reports-api';
import { formatDashboardAmount } from './dashboard-formatters';

@Component({
  selector: 'app-dashboard-visualizations',
  imports: [RouterLink],
  templateUrl: './dashboard-visualizations.html',
  styleUrl: './dashboard-visualizations.scss',
})
export class DashboardVisualizations {
  readonly data = input.required<AdminReportResponse>();
  readonly currencies = computed(() =>
    this.data().financial.approvedPaymentsByPaidAt.map((item) => item.currency),
  );

  sessionBarWidth(count: number) {
    const maximum = Math.max(0, ...this.data().operational.dailySessions.map((item) => item.count));
    return maximum ? Math.max(count ? 1 : 0, (count / maximum) * 100) : 0;
  }

  statusBarWidth(count: number) {
    const maximum = Math.max(
      0,
      ...this.data().operational.sessionsByStatus.map((item) => item.count),
    );
    return maximum ? (count / maximum) * 100 : 0;
  }

  zoneBarWidth(count: number) {
    const maximum = Math.max(
      0,
      ...this.data().operational.sessionsByZone.map((item) => item.count),
    );
    return maximum ? (count / maximum) * 100 : 0;
  }

  dailyPayments(currency: string) {
    return this.data().financial.dailyApprovedPayments.filter((item) => item.currency === currency);
  }

  paymentBarWidth(amount: string, currency: string) {
    const current = DashboardVisualizations.toMinorUnits(amount);
    const maximum = this.dailyPayments(currency).reduce((largest, item) => {
      const value = DashboardVisualizations.toMinorUnits(item.amount);
      return value > largest ? value : largest;
    }, 0n);
    if (maximum <= 0n || current <= 0n) return 0;
    return Number((current * 10_000n) / maximum) / 100;
  }

  formatAmount(amount: string, currency: string) {
    return formatDashboardAmount(amount, currency);
  }

  formatDate(value: string) {
    const [, month, day] = value.split('-');
    return `${day}/${month}`;
  }

  private static toMinorUnits(value: string) {
    if (!/^\d+(\.\d{1,2})?$/.test(value)) return 0n;
    const [whole, fraction = ''] = value.split('.');
    return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  }
}
