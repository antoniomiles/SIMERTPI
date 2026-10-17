export interface PortalEnvironment {
  readonly name: 'local' | 'dev' | 'production';
  readonly apiBaseUrl: string;
  readonly administrativeApiEnabled: boolean;
  readonly localPreview: boolean;
}
