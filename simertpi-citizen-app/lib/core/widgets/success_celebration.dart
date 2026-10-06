import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_tokens.dart';

/// Brief, decorative celebration. Never conveys operation status on its own.
class SuccessCelebration extends StatefulWidget {
  const SuccessCelebration({super.key, required this.child});
  final Widget child;
  @override
  State<SuccessCelebration> createState() => _SuccessCelebrationState();
}

class _SuccessCelebrationState extends State<SuccessCelebration>
    with SingleTickerProviderStateMixin {
  late final AnimationController animation = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 2),
  );
  bool started = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      animation.stop();
      animation.value = 1;
    } else if (!started) {
      started = true;
      animation.forward();
    }
  }

  @override
  void dispose() {
    animation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Stack(
    alignment: Alignment.center,
    children: [
      widget.child,
      if (!MediaQuery.disableAnimationsOf(context))
        Positioned.fill(
          child: IgnorePointer(
            child: ExcludeSemantics(
              child: AnimatedBuilder(
                animation: animation,
                builder: (context, _) =>
                    CustomPaint(painter: _Confetti(animation.value)),
              ),
            ),
          ),
        ),
    ],
  );
}

class _Confetti extends CustomPainter {
  _Confetti(this.progress);
  final double progress;
  static const colors = [
    AppColors.action,
    AppColors.available,
    AppColors.endingSoon,
    AppColors.occupied,
  ];
  @override
  void paint(Canvas canvas, Size size) {
    if (progress >= 1) return;
    for (int i = 0; i < 20; i++) {
      final angle = i * math.pi * 2 / 20;
      final distance = 35 + progress * (40 + i % 4 * 12);
      final x = size.width / 2 + math.cos(angle) * distance;
      final y =
          size.height / 2 +
          math.sin(angle) * distance +
          progress * progress * 22;
      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(angle + progress * 3);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          const Rect.fromLTWH(-2, -4, 4, 8),
          const Radius.circular(1),
        ),
        Paint()
          ..color = colors[i % colors.length].withValues(
            alpha: (1 - progress).clamp(0, 1),
          ),
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_Confetti oldDelegate) => progress != oldDelegate.progress;
}
