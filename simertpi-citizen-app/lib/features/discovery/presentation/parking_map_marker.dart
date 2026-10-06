import 'package:flutter/material.dart';

import '../../../core/theme/app_tokens.dart';
import '../data/parking_catalog.dart';
import 'space_status.dart';

class ParkingMapMarker extends StatelessWidget {
  const ParkingMapMarker({super.key, required this.space, this.onTap});
  final CatalogSpace space;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) {
    final suffix =
        RegExp(r'(\d+)$').firstMatch(space.code)?.group(1) ?? space.number;
    return Semantics(
      button: true,
      label: '${space.code}, ${spaceStatus(space)}',
      child: Tooltip(
        message: 'Espacio ${space.number}',
        child: InkWell(
          onTap: onTap,
          child: CustomPaint(
            painter: _Pin(
              space.operationalStatus == 'ENDING_SOON'
                  ? AppColors.endingSoonMarker
                  : spaceColor(space),
            ),
            child: SizedBox(
              width: 48,
              height: 56,
              child: Padding(
                padding: const EdgeInsets.only(bottom: 14),
                child: Center(
                  child: Text(
                    suffix,
                    style: TextStyle(
                      color: space.operationalStatus == 'ENDING_SOON'
                          ? AppColors.ink
                          : AppColors.surface,
                      fontWeight: FontWeight.w700,
                      fontSize: 13,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Pin extends CustomPainter {
  const _Pin(this.color);
  final Color color;
  @override
  void paint(Canvas c, Size s) {
    final p = Path()
      ..moveTo(s.width / 2, s.height - 2)
      ..cubicTo(4, 34, 2, 26, 4, 18)
      ..cubicTo(8, -2, 40, -2, 44, 18)
      ..cubicTo(46, 26, 44, 34, s.width / 2, s.height - 2)
      ..close();
    c.drawPath(p, Paint()..color = color);
    c.drawPath(
      p,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
  }

  @override
  bool shouldRepaint(_Pin old) => old.color != color;
}
