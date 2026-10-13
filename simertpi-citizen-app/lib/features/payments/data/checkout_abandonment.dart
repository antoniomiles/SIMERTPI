import '../../../core/network/api_client.dart';

abstract interface class CheckoutAbandonment {
  Future<void> abandon(String parkingSessionId);
}

class ApiCheckoutAbandonment implements CheckoutAbandonment {
  const ApiCheckoutAbandonment(this.client);
  final ApiClient client;

  @override
  Future<void> abandon(String parkingSessionId) async {
    await client.request(
      ApiMethod.post,
      'parking/sessions/$parkingSessionId/abandon',
    );
  }
}
