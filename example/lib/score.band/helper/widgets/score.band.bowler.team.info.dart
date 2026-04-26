
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class ScorebandBowlerTeamInfo extends ConsumerWidget {
  final String playerName;
  final int wicket;
  final int runsGiven;
  final String overCount;
  final bool mediumMode;
  final double fontSize;
  const ScorebandBowlerTeamInfo({
    super.key,
    required this.playerName,
    required this.wicket,
    required this.runsGiven,
    required this.overCount,
    required this.mediumMode,
    required this.fontSize,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final textStyleForBand = TextStyle(
      fontFamily: 'RobotoCondensed',
      color: Colors.white,
    );
    var listWidgets = <Widget>[];
    if (1 == 1) {

      final targetText =
          '';

      listWidgets = [
        Text(
          'TARGET',
          style: textStyleForBand.copyWith(
            fontSize: fontSize - 3,
            fontWeight: FontWeight.w300,
          ),
        ),
        Text(
          targetText,
          style: textStyleForBand.copyWith(
            fontSize: fontSize,
            fontWeight: FontWeight.w500,
          ),
        )
      ];
    } 
    return LayoutBuilder(
      builder: (context, cons) {
        final availableWidth = cons.maxWidth; // exact available width
        return Row(
          // mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Container(
              width: availableWidth * 0.32,
              alignment: Alignment.center,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.center,
                children: listWidgets,
              ),
            ),
            Expanded(
              // takes all remaining space + truncates

              child: Text(
                playerName,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: textStyleForBand.copyWith(
                  fontSize: fontSize,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            Text(
              '$wicket-$runsGiven $overCount',
              style: textStyleForBand.copyWith(
                fontSize: fontSize,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        );
      },
    );
  }
}
