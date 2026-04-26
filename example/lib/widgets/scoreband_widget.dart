// import 'package:flutter/material.dart';

// class ScorebandWidget extends StatelessWidget {
//   const ScorebandWidget({
//     super.key,
//     required this.repaintBoundaryKey,
//     required this.homeTeam,
//     required this.awayTeam,
//     required this.homeScore,
//     required this.awayScore,
//     required this.matchTime,
//     required this.streaming,
//   });

//   final GlobalKey repaintBoundaryKey;
//   final String homeTeam;
//   final String awayTeam;
//   final int homeScore;
//   final int awayScore;
//   final int matchTime;
//   final bool streaming;

//   @override
//   Widget build(BuildContext context) {
//     return RepaintBoundary(
//       key: repaintBoundaryKey,
//       child: Opacity(
//         opacity: streaming ? 1.0 : 0.0,
//         child: Container(
//           padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
//           decoration: BoxDecoration(
//             gradient: const LinearGradient(
//               colors: [Color(0xFF1E3A5F), Color(0xFF2E5A8F)],
//               begin: Alignment.centerLeft,
//               end: Alignment.centerRight,
//             ),
//             borderRadius: BorderRadius.circular(12),
//             boxShadow: [
//               BoxShadow(
//                 color: Colors.black.withValues(alpha: 0.3),
//                 blurRadius: 8,
//                 offset: const Offset(0, 4),
//               ),
//             ],
//           ),
//           child: Row(
//             mainAxisAlignment: MainAxisAlignment.spaceBetween,
//             children: [
//               Column(
//                 mainAxisSize: MainAxisSize.min,
//                 crossAxisAlignment: CrossAxisAlignment.start,
//                 children: [
//                   Text(
//                     homeTeam,
//                     style: const TextStyle(
//                       color: Colors.white,
//                       fontSize: 14,
//                       fontWeight: FontWeight.bold,
//                     ),
//                   ),
//                   Text(
//                     '$homeScore',
//                     style: const TextStyle(
//                       color: Colors.white,
//                       fontSize: 28,
//                       fontWeight: FontWeight.bold,
//                     ),
//                   ),
//                 ],
//               ),
//               Column(
//                 mainAxisSize: MainAxisSize.min,
//                 children: [
//                   Text(
//                     "$matchTime'",
//                     style: const TextStyle(
//                       color: Colors.white70,
//                       fontSize: 16,
//                       fontWeight: FontWeight.w500,
//                     ),
//                   ),
//                   const Text(
//                     'LIVE',
//                     style: TextStyle(
//                       color: Colors.red,
//                       fontSize: 12,
//                       fontWeight: FontWeight.bold,
//                     ),
//                   ),
//                 ],
//               ),
//               Column(
//                 mainAxisSize: MainAxisSize.min,
//                 crossAxisAlignment: CrossAxisAlignment.end,
//                 children: [
//                   Text(
//                     awayTeam,
//                     style: const TextStyle(
//                       color: Colors.white,
//                       fontSize: 14,
//                       fontWeight: FontWeight.bold,
//                     ),
//                   ),
//                   Text(
//                     '$awayScore',
//                     style: const TextStyle(
//                       color: Colors.white,
//                       fontSize: 28,
//                       fontWeight: FontWeight.bold,
//                     ),
//                   ),
//                 ],
//               ),
//             ],
//           ),
//         ),
//       ),
//     );
//   }
// }
