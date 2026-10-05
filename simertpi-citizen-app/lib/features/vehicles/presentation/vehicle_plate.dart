import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/theme/app_tokens.dart';

String visualPlate(String plate) {
  final value = plate.trim().toUpperCase();
  final match = RegExp(r'^([A-Z]+)([0-9]+)$').firstMatch(value);
  return match == null ? value : '${match[1]} ${match[2]}';
}

// Prefix-based offset conversion preserves selection even when uppercase expands.
class UppercasePlateFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    final text = newValue.text.toUpperCase();
    int offset(int value) => value < 0
        ? value
        : newValue.text
              .substring(0, value.clamp(0, newValue.text.length))
              .toUpperCase()
              .length;
    return TextEditingValue(
      text: text,
      selection: TextSelection(
        baseOffset: offset(newValue.selection.baseOffset),
        extentOffset: offset(newValue.selection.extentOffset),
      ),
      composing: newValue.composing.isValid
          ? TextRange(
              start: offset(newValue.composing.start),
              end: offset(newValue.composing.end),
            )
          : TextRange.empty,
    );
  }
}

class VehiclePlate extends StatelessWidget {
  const VehiclePlate({super.key, required this.plate});
  final String plate;
  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: AspectRatio(
      aspectRatio: 2.1,
      child: Container(
        padding: const EdgeInsets.all(AppSpace.sm),
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.ink, width: 2),
          borderRadius: BorderRadius.circular(AppSpace.sm),
        ),
        alignment: Alignment.center,
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: Text(
            visualPlate(plate),
            style: const TextStyle(
              color: AppColors.ink,
              fontSize: 28,
              fontWeight: FontWeight.w900,
            ),
          ),
        ),
      ),
    ),
  );
}
