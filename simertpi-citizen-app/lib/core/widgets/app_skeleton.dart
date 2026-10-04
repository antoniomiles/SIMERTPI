import 'package:flutter/material.dart';

import '../theme/app_tokens.dart';
import 'app_layout.dart';

class AppSkeleton extends StatelessWidget {
  const AppSkeleton({super.key, this.width, this.height = 16});
  final double? width;
  final double height;
  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: AppColors.skeleton,
        borderRadius: BorderRadius.circular(AppSpace.xs),
      ),
    ),
  );
}

class SkeletonCard extends StatelessWidget {
  const SkeletonCard({super.key});
  @override
  Widget build(BuildContext context) => Semantics(
    label: 'Cargando información',
    liveRegion: true,
    child: AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const FractionallySizedBox(widthFactor: .45, child: AppSkeleton()),
          const SizedBox(height: AppSpace.md),
          const AppSkeleton(height: 24),
          const SizedBox(height: AppSpace.sm),
          const FractionallySizedBox(widthFactor: .7, child: AppSkeleton()),
        ],
      ),
    ),
  );
}

class SkeletonList extends StatelessWidget {
  const SkeletonList({super.key, this.count = 3});
  final int count;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (var i = 0; i < count; i++) ...[
        if (i > 0) const SizedBox(height: AppSpace.md),
        const SkeletonCard(),
      ],
    ],
  );
}
