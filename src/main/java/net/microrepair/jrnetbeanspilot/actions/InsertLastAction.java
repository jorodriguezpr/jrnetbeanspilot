// JR NetBeans Pilot (jrnetbeanspilot)
// Copyright 2026 Jose Rodriguez Arroyo
// Developer: Jose Rodriguez Arroyo <jrpcone@gmail.com>
// Website: https://microrepair.net | GitHub: https://github.com/jorodriguezpr
// Project: https://github.com/jorodriguezpr/jrnetbeanspilot
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// SPDX-License-Identifier: Apache-2.0
package net.microrepair.jrnetbeanspilot.actions;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import net.microrepair.jrnetbeanspilot.Pilot;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;

@ActionID(category = "Tools", id = "net.microrepair.jrnetbeanspilot.actions.InsertLastAction")
@ActionRegistration(displayName = "Insert Last Answer's Code Again")
@ActionReferences({
    @ActionReference(path = "Menu/Tools/Copilot App", position = 300)
})
public final class InsertLastAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        Pilot.insertLast();
    }
}
