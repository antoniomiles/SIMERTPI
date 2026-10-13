import 'package:flutter/widgets.dart';

/// Input from the software keyboard does not produce Flutter pointer events.
/// Only user-edit callbacks participate; controller writes never count.
class HumanActivity extends InheritedWidget {
  const HumanActivity({
    super.key,
    required this.onActivity,
    required super.child,
  });
  final VoidCallback onActivity;
  static void record(BuildContext context) =>
      context.getInheritedWidgetOfExactType<HumanActivity>()?.onActivity();
  @override
  bool updateShouldNotify(HumanActivity oldWidget) => false;
}
