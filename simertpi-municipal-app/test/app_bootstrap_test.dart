import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/app/municipal_app.dart';
import 'package:simertpi_municipal_app/core/config/municipal_app_config.dart';

void main() {
  testWidgets('bootstrap is informative and does not simulate authentication', (
    tester,
  ) async {
    const config = MunicipalAppConfig(
      environment: MunicipalEnvironment.development,
      apiBaseUrl: '',
    );

    await tester.pumpWidget(const SimertpiMunicipalApp(config: config));

    expect(find.text('Aplicación municipal en preparación'), findsOneWidget);
    expect(find.textContaining('todavía no están habilitados'), findsOneWidget);
    expect(find.textContaining('Backend sin configurar'), findsOneWidget);
    expect(find.text('Iniciar sesión'), findsNothing);
    expect(find.text('Turno activo'), findsNothing);
  });
}
