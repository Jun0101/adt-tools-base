/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.chartlib.visual;

import com.android.annotations.NonNull;
import com.android.tools.chartlib.AnimatedComponent;
import com.android.tools.chartlib.AnimatedTimeRange;
import com.android.tools.chartlib.AxisComponent;
import com.android.tools.chartlib.GridComponent;
import com.android.tools.chartlib.LineChart;
import com.android.tools.chartlib.MemoryAxisDomain;
import com.android.tools.chartlib.Range;
import com.android.tools.chartlib.RangeScrollbar;
import com.android.tools.chartlib.SelectionComponent;
import com.android.tools.chartlib.TimeAxisDomain;
import com.android.tools.chartlib.model.LineChartData;
import com.android.tools.chartlib.model.RangedContinuousSeries;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;

public class AxisLineChartVisualTest extends VisualTest {

    private static final float ZOOM_FACTOR = 0.1f;

    private static final int AXIS_SIZE = 100;

    private final long mStartTimeMs;

    @NonNull
    private final Range mXRange;

    @NonNull
    private final Range mXGlobalRange;

    @NonNull
    private final AnimatedTimeRange mAnimatedTimeRange;

    @NonNull
    private final Range mXSelectionRange;

    @NonNull
    private final LineChart mLineChart;

    @NonNull
    private final LineChartData mData;

    @NonNull
    private AxisComponent mMemoryAxis1;

    @NonNull
    private AxisComponent mMemoryAxis2;

    @NonNull
    private AxisComponent mTimeAxis;

    @NonNull
    private GridComponent mGrid;

    @NonNull
    private SelectionComponent mSelection;

    @NonNull
    private final RangeScrollbar mScrollbar;

    public AxisLineChartVisualTest() {
        mData = new LineChartData();
        mLineChart = new LineChart(mData);

        mStartTimeMs = System.currentTimeMillis();
        mXRange = new Range(0, 0);
        mXGlobalRange = new Range(0, 0);
        mAnimatedTimeRange = new AnimatedTimeRange(mXGlobalRange, mStartTimeMs);

        mScrollbar = new RangeScrollbar(mXGlobalRange, mXRange);

        // add horizontal time axis
        mTimeAxis = new AxisComponent(mXRange,
                mXGlobalRange,
                "TIME",
                AxisComponent.AxisOrientation.BOTTOM,
                AXIS_SIZE,
                AXIS_SIZE,
                false,
                new TimeAxisDomain(10, 50, 5));

        // left memory data + axis
        Range yRange1Animatable = new Range(0, 100);
        mMemoryAxis1 = new AxisComponent(yRange1Animatable,
                yRange1Animatable,
                "MEMORY1",
                AxisComponent.AxisOrientation.LEFT,
                AXIS_SIZE,
                AXIS_SIZE,
                true,
                new MemoryAxisDomain(10, 50, 5));
        RangedContinuousSeries ranged1 = new RangedContinuousSeries(mXRange, yRange1Animatable);
        mData.add(ranged1);

        // right memory data + axis
        Range yRange2Animatable = new Range(0, 100);
        mMemoryAxis2 = new AxisComponent(yRange2Animatable,
                yRange2Animatable,
                "MEMORY2",
                AxisComponent.AxisOrientation.RIGHT,
                AXIS_SIZE,
                AXIS_SIZE,
                true,
                new MemoryAxisDomain(10, 50, 5));
        RangedContinuousSeries ranged2 = new RangedContinuousSeries(mXRange, yRange2Animatable);
        mData.add(ranged2);

        mGrid = new GridComponent();
        mGrid.addAxis(mTimeAxis);
        mGrid.addAxis(mMemoryAxis1);

        mXSelectionRange = new Range(0, 0);
        mSelection = new SelectionComponent(mTimeAxis, mXSelectionRange, mXGlobalRange, mXRange);

        // Note: the order below is important as some components depend on
        // others to be updated first. e.g. the ranges need to be updated before the axes.
        // The comment on each line highlights why the component needs to be in that position.
        mChoreographer.register(mAnimatedTimeRange);     // Update global time range immediate.
        mChoreographer.register(mSelection);             // Update selection range immediate.
        mChoreographer.register(mScrollbar);             // Update current range immediate.
        mChoreographer.register(mLineChart);             // Set y's interpolation values.
        mChoreographer.register(yRange1Animatable);      // Interpolate y1.
        mChoreographer.register(yRange2Animatable);      // Interpolate y2.
        mChoreographer.register(mTimeAxis);              // Read ranges.
        mChoreographer.register(mMemoryAxis1);           // Read ranges.
        mChoreographer.register(mMemoryAxis2);           // Read ranges.
        mChoreographer.register(mGrid);                  // No-op.
        mChoreographer.register(mXRange);                // Reset flags.
        mChoreographer.register(mXGlobalRange);          // Reset flags.
        mChoreographer.register(mXSelectionRange);       // Reset flags.
    }

    @Override
    void registerComponents(List<AnimatedComponent> components) {
        components.add(mLineChart);
        components.add(mSelection);
        components.add(mTimeAxis);
        components.add(mMemoryAxis1);
        components.add(mMemoryAxis2);
        components.add(mGrid);
    }

    @Override
    public String getName() {
        return "AxisLineChart";
    }

    @Override
    protected JPanel create() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());

        JLayeredPane mockTimelinePane = createMockTimeline();
        panel.add(mockTimelinePane, BorderLayout.CENTER);

        final JPanel controls = new JPanel();
        LayoutManager manager = new BoxLayout(controls, BoxLayout.Y_AXIS);
        controls.setLayout(manager);
        panel.add(controls, BorderLayout.WEST);

        final AtomicInteger variance = new AtomicInteger(10);
        final AtomicInteger delay = new AtomicInteger(10);
        new Thread() {
            @Override
            public void run() {
                super.run();
                try {
                    while (true) {
                        //  Insert new data point at now.
                        long now = System.currentTimeMillis() - mStartTimeMs;
                        int v = variance.get();
                        for (RangedContinuousSeries rangedSeries : mData.series()) {
                            int size = rangedSeries.getSeries().size();
                            long last = size > 0 ? rangedSeries.getSeries().getY(size - 1) : 0;
                            float delta = (float) Math.random() * variance.get() - v * 0.45f;
                            rangedSeries.getSeries().add(now, last + (long) delta);
                        }

                        Thread.sleep(delay.get());
                    }
                } catch (InterruptedException e) {
                }
            }
        }.start();

        controls.add(VisualTests.createVariableSlider("Delay", 10, 5000, new VisualTests.Value() {
            @Override
            public void set(int v) {
                delay.set(v);
            }

            @Override
            public int get() {
                return delay.get();
            }
        }));
        controls.add(VisualTests.createVariableSlider("Variance", 0, 50, new VisualTests.Value() {
            @Override
            public void set(int v) {
                variance.set(v);
            }

            @Override
            public int get() {
                return variance.get();
            }
        }));
        controls.add(VisualTests.createCheckbox("Stable Scroll", new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent itemEvent) {
                mScrollbar.setStableScrolling(itemEvent.getStateChange() == ItemEvent.SELECTED);
            }
        }));
        controls.add(VisualTests.createButton("Zoom In Test", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mScrollbar.zoom(-ZOOM_FACTOR);
            }
        }));
        controls.add(VisualTests.createButton("Zoom Out Test", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mScrollbar.zoom(ZOOM_FACTOR);
            }
        }));

        controls.add(
                new Box.Filler(new Dimension(0, 0), new Dimension(300, Integer.MAX_VALUE),
                        new Dimension(300, Integer.MAX_VALUE)));

        return panel;
    }

    private JLayeredPane createMockTimeline() {
        JLayeredPane timelinePane = new JLayeredPane();

        timelinePane.add(mMemoryAxis1);
        timelinePane.add(mMemoryAxis2);
        timelinePane.add(mTimeAxis);
        timelinePane.add(mLineChart);
        timelinePane.add(mSelection);
        timelinePane.add(mGrid);
        timelinePane.add(mScrollbar);
        timelinePane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                JLayeredPane host = (JLayeredPane)e.getComponent();
                if (host != null) {
                    Dimension dim = host.getSize();
                    for (Component c : host.getComponents()) {
                        if (c instanceof AxisComponent) {
                            AxisComponent axis = (AxisComponent)c;
                            switch (axis.getOrientation()) {
                                case LEFT:
                                    axis.setBounds(0, 0, AXIS_SIZE, dim.height);
                                    break;
                                case BOTTOM:
                                    axis.setBounds(0, dim.height - AXIS_SIZE, dim.width, AXIS_SIZE);
                                    break;
                                case RIGHT:
                                    axis.setBounds(dim.width - AXIS_SIZE, 0, AXIS_SIZE, dim.height);
                                    break;
                                case TOP:
                                    axis.setBounds(0, 0, dim.width, AXIS_SIZE);
                                    break;
                            }
                        } else if (c instanceof RangeScrollbar) {
                            int sbHeight = c.getPreferredSize().height;
                            c.setBounds(0, dim.height - sbHeight, dim.width, sbHeight);
                        } else {
                            c.setBounds(AXIS_SIZE, AXIS_SIZE, dim.width - AXIS_SIZE * 2,
                                    dim.height - AXIS_SIZE * 2);
                        }
                    }
                }
            }
        });

        return timelinePane;
    }
}